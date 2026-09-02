package fun.commons.tokengateway.demo;

import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.contract.SettleVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存计费账本 (demo 桩): 预扣持有 → 结算实扣 / 退款释放.
 *
 * <p>幂等按 preConsumeId (与真 billing 面同语义); 特殊凭证演练负路径:
 * {@code sk-poor} → 余额不足 (10617), {@code sk-banned} → 凭证无效.
 * 状态经 /demo/state 暴露, 供冒烟脚本断言 "预扣-终态" 闭环零差异.
 */
@Slf4j
@Component
public class DemoBillingLedger {

    /** 预扣记录状态. */
    public enum HoldState {
        OPEN, SETTLED, REFUNDED
    }

    public record Hold(String preConsumeId, String userId, BigDecimal amount, HoldState state) {
    }

    private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
    /** 在途 hold 占用 (userId → sum(amount of OPEN holds)); 可用 = 余额 − 占用. */
    private final Map<String, BigDecimal> heldByUser = new ConcurrentHashMap<>();
    private final Map<String, Hold> holds = new ConcurrentHashMap<>();

    /** 任务面全额定价 (demo 一口价); LLM 面按估算 token 计. */
    private static final BigDecimal TASK_FLAT_PRICE = new BigDecimal("1.00");
    private static final BigDecimal TOKEN_PRICE = new BigDecimal("0.000001");

    private BigDecimal balanceOf(String userId) {
        return balances.computeIfAbsent(userId == null ? "anon" : userId,
                // sk-poor 演练用户: 余额低于任务面一口价 → pre-consume 拒绝 (10617 演练路径)
                k -> "demo-poor".equals(k) ? new BigDecimal("0.0001") : new BigDecimal("100.00"));
    }

    public PreConsumeVO preConsume(PreConsumeRequest req) {
        BigDecimal amount = estimate(req);
        String user = req.getUserId() == null ? "anon" : req.getUserId();
        BigDecimal balance = balanceOf(user);
        BigDecimal held = heldByUser.getOrDefault(user, BigDecimal.ZERO);
        BigDecimal available = balance.subtract(held);
        if (available.compareTo(amount) < 0) {
            log.info("[DemoBilling] 余额不足: user={}, balance={}, held={}, need={}",
                    user, balance, held, amount);
            PreConsumeVO vo = new PreConsumeVO();
            vo.setSuccess(false);
            vo.setFailReason("demo 余额不足 (available=" + available + ", need=" + amount + ")");
            vo.setEstimatedQuota(amount);
            return vo;
        }
        String id = "pc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        holds.put(id, new Hold(id, user, amount, HoldState.OPEN));
        heldByUser.merge(user, amount, BigDecimal::add);
        PreConsumeVO vo = new PreConsumeVO();
        vo.setSuccess(true);
        vo.setPreConsumeId(id);
        vo.setEstimatedQuota(amount);
        return vo;
    }

    public SettleVO settle(SettleRequest req) {
        SettleVO vo = new SettleVO();
        Hold hold = req.getPreConsumeId() == null ? null : holds.get(req.getPreConsumeId());
        if (hold == null || hold.state() != HoldState.OPEN) {
            vo.setCreditConsumed(BigDecimal.ZERO);
            return vo;
        }
        BigDecimal actual = req.isSuccess()
                ? hold.amount()   // demo: 结算不超预扣; 任务全额, LLM 面按预扣即付
                : BigDecimal.ZERO;
        releaseHold(hold);
        holds.put(hold.preConsumeId(),
                new Hold(hold.preConsumeId(), hold.userId(), hold.amount(), HoldState.SETTLED));
        balances.merge(hold.userId(), actual.negate(), BigDecimal::add);
        vo.setCreditConsumed(actual);
        log.info("[DemoBilling] 结算: preConsumeId={}, charged={}", hold.preConsumeId(), actual);
        return vo;
    }

    /** 退款幂等: 同 preConsumeId 重复退款只生效一次. */
    public void refund(RefundRequest req) {
        Hold hold = req.getPreConsumeId() == null ? null : holds.get(req.getPreConsumeId());
        if (hold == null || hold.state() != HoldState.OPEN) {
            return; // 幂等跳过
        }
        releaseHold(hold);
        holds.put(hold.preConsumeId(),
                new Hold(hold.preConsumeId(), hold.userId(), hold.amount(), HoldState.REFUNDED));
        log.info("[DemoBilling] 退款: preConsumeId={}, released={} ({})",
                hold.preConsumeId(), hold.amount(), req.getReason());
    }

    private void releaseHold(Hold hold) {
        heldByUser.computeIfPresent(hold.userId(), (k, v) -> v.subtract(hold.amount()));
    }

    /** 对账视图: OPEN 预扣清单 (冒烟脚本断言应为空). */
    public Map<String, Hold> openHolds() {
        return holds.entrySet().stream()
                .filter(e -> e.getValue().state() == HoldState.OPEN)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Map<String, BigDecimal> balances() {
        return Map.copyOf(balances);
    }

    public void reset() {
        balances.clear();
        heldByUser.clear();
        holds.clear();
    }

    private static BigDecimal estimate(PreConsumeRequest req) {
        if (req.getEstimatedPromptTokens() == 0 && req.getEstimatedCompletionTokens() == 0) {
            return TASK_FLAT_PRICE;
        }
        return TOKEN_PRICE.multiply(BigDecimal.valueOf(
                (long) req.getEstimatedPromptTokens() + req.getEstimatedCompletionTokens()));
    }
}
