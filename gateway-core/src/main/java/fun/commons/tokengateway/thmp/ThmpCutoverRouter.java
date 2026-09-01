package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.OwnerType;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 灰度切流路由器 (22 号 S2-W3 前置骨架).
 *
 * <p>灰度判定: model ∈ cutoverModels 且 bucket(model|requestId) &lt; cutoverPercent
 * (String.hashCode 确定性分桶 — 同请求重放同判定, 无 RNG).
 *
 * <p>路由构建: SWR 缓存取候选 → 逐候选解 key_cipher_tenant (ThmpKeyCipher, 解不开跳过 —
 * BYOK 逐租户口令未决#12 决议前公共渠道即通) → 首个可解候选转旧链路同构 DistributeVO
 * (channelId/baseUrl/protocol/明文 key; ownerType 恒 PLATFORM — 灰度面为公共 SKU).
 *
 * <p>失败语义: 任一步失败 → Mono.empty() → 调用方回旧 distribute (22 号风险表: 一键回旧).
 * 计费双轨: 切流请求仍走旧 preConsume/settle saga, channelId 透传 THMP id (灰度期对账随 W3 运营).
 */
@Slf4j
public class ThmpCutoverRouter implements ThmpCutover {

    private final ThmpContractProperties props;
    private final ThmpCandidateCache cache;
    private final ThmpKeyCipher keyCipher;

    public ThmpCutoverRouter(ThmpContractProperties props, ThmpCandidateCache cache,
                             ThmpKeyCipher keyCipher) {
        this.props = props;
        this.cache = cache;
        this.keyCipher = keyCipher;
    }

    @Override
    public boolean shouldCut(String model, String tenantId, String requestId) {
        List<String> models = props.getCutoverModels();
        if (models == null || models.isEmpty() || props.getCutoverPercent() <= 0
                || model == null || requestId == null) {
            return false;
        }
        if (!models.contains(model)) {
            return false;
        }
        int bucket = Math.floorMod((model + "|" + requestId).hashCode(), 100);
        return bucket < props.getCutoverPercent();
    }

    @Override
    public Mono<DistributeVO> route(String model, String tenantId, String requestId) {
        if (!shouldCut(model, tenantId, requestId)) {
            return Mono.empty();
        }
        String tid = tenantId == null || tenantId.isBlank() ? "0" : tenantId;
        return cache.get(model, tid)
                .flatMap(result -> firstRoutable(result.candidates()))
                .onErrorResume(e -> {
                    log.error("[THMP-CUTOVER] resolve 失败回旧链路: model={} err={}", model, e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * 首个密钥可解候选 → DistributeVO; 全部不可解 (如 BYOK 无对应口令) → 空.
     */
    private Mono<DistributeVO> firstRoutable(List<ThmpContractClient.Candidate> candidates) {
        if (candidates == null) {
            return Mono.empty();
        }
        for (ThmpContractClient.Candidate c : candidates) {
            if (c.keys() == null || c.keys().isEmpty()) {
                continue;
            }
            for (ThmpContractClient.KeyEntry key : c.keys()) {
                try {
                    String plain = keyCipher.decrypt(0L, key.key_cipher_tenant());
                    return Mono.just(toVO(c, plain));
                } catch (Exception e) {
                    log.warn("[THMP-CUTOVER] 候选密钥不可解 (跨口令/BYOK?) channel={} err={}",
                            c.channel_id(), e.getMessage());
                }
            }
        }
        return Mono.empty();
    }

    private static DistributeVO toVO(ThmpContractClient.Candidate c, String plainKey) {
        return DistributeVO.builder()
                .channelId(c.channel_id())
                .baseUrl(c.upstream_base_url())
                .protocol(c.protocol_type())
                .apiKey(plainKey)
                .ownerType(OwnerType.PLATFORM)
                .build();
    }
}
