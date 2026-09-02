package fun.commons.tokengateway.demo;

import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoBillingLedger 单测 (预扣→结算/退款 saga + 幂等 + 对账视图).
 */
@DisplayName("DemoBillingLedger")
class DemoBillingLedgerTest {

    private DemoBillingLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new DemoBillingLedger();
    }

    private static PreConsumeRequest taskReq(String userId) {
        return PreConsumeRequest.builder().userId(userId).model("vid-mock-1").build();
    }

    @Test
    @DisplayName("任务全额预扣 → 退款释放 → 对账零 OPEN")
    void preConsumeThenRefund() {
        PreConsumeVO vo = ledger.preConsume(taskReq("u1"));
        assertThat(vo.isSuccess()).isTrue();
        assertThat(ledger.openHolds()).hasSize(1);

        ledger.refund(RefundRequest.builder()
                .preConsumeId(vo.getPreConsumeId()).reason("task FAILED").build());
        assertThat(ledger.openHolds()).isEmpty();

        // 幂等: 重复退款不二次生效
        ledger.refund(RefundRequest.builder()
                .preConsumeId(vo.getPreConsumeId()).reason("again").build());
        assertThat(ledger.openHolds()).isEmpty();
    }

    @Test
    @DisplayName("预扣 → 结算实扣 (任务一口价) → 余额减少")
    void preConsumeThenSettle() {
        PreConsumeVO vo = ledger.preConsume(taskReq("u1"));
        ledger.settle(SettleRequest.builder()
                .preConsumeId(vo.getPreConsumeId()).success(true).build());
        assertThat(ledger.openHolds()).isEmpty();
        assertThat(ledger.balances().get("u1")).isLessThan(new BigDecimal("100.00"));
    }

    @Test
    @DisplayName("余额不足 → success=false + failReason (网关侧映射 10617)")
    void insufficientBalance() {
        // demo-poor 余额 0.0001 由 controller 层凭证决定; 账本直测超额路径:
        for (int i = 0; i < 200; i++) {
            ledger.preConsume(taskReq("u2"));
        }
        PreConsumeVO vo = ledger.preConsume(taskReq("u2"));
        assertThat(vo.isSuccess()).isFalse();
        assertThat(vo.getFailReason()).contains("余额不足");
    }
}
