package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.contract.DistributeVO;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 影子比对器 (22 号 S2-W1 → W2 归零清单数据源).
 *
 * <p>比对维度 (ID 空间不同 — 旧 mmxp_channel.id 与 THMP thmp_channel.id 无映射, 以 **上游 base_url**
 * 为锚判定"同一渠道", 顺序/延迟/明文 key 差异按白名单不比对, 见 CODING-PROGRESS 影子比对映射表):
 * <ul>
 *   <li>MATCH — 旧 baseUrl 命中 THMP 候选集且 protocol 一致</li>
 *   <li>DIFF — 命中但 protocol 不一致 / 未命中 (diffs 列明差异字段)</li>
 *   <li>THMP_EMPTY — 中台无候选 (全 BLOCKED 或无映射, W2 归零重点)</li>
 *   <li>THMP_ERROR — 中台调用失败 (超时/验签/信封错)</li>
 * </ul>
 *
 * <p>租户归一: 旧世界租户在 THMP 无 ID 映射, 非数字一律归 "0" 比对公共渠道;
 * BYOK 专属渠道比对随 W3 注册 (tenant_id≠0) 后启用.
 *
 * <p>装配走 {@link ThmpGatewayConfiguration} (按 gateway.thmp.enabled 条件化,
 * 本类不打 @Component, 关闭态零装配).
 */
@Slf4j
public class ThmpShadowComparator implements ThmpShadow {

    private final ThmpCandidateCache cache;

    public ThmpShadowComparator(ThmpCandidateCache cache) {
        this.cache = cache;
    }

    @Override
    public void compare(String model, String tenantId, DistributeVO oldRoute) {
        if (model == null || model.isBlank() || oldRoute == null) {
            return;
        }
        verdict(model, tenantId, oldRoute)
                .subscribe(verdict -> log.info("[THMP-SHADOW] {}", verdict.logLine()),
                        e -> log.warn("[THMP-SHADOW] 比对器内部异常 (吞): model={}, err={}",
                                model, e.getMessage()));
    }

    /**
     * 比对结果 (Mono 供测试确定性断言; compare 内部订阅).
     */
    public Mono<Verdict> verdict(String model, String tenantId, DistributeVO oldRoute) {
        String tid = normalizeTenant(tenantId);
        return cache.get(model, tid)
                .map(result -> judge(model, tid, oldRoute, result))
                .onErrorResume(e -> Mono.just(Verdict.of("THMP_ERROR", model, tid,
                        oldRoute, List.of(), e.getMessage())));
    }

    /**
     * 判定: base_url 命中 + protocol 一致 → MATCH; 否则逐项列差异.
     */
    static Verdict judge(String model, String tenantId, DistributeVO oldRoute,
                         ThmpContractClient.ResolveResult result) {
        if (!result.hasCandidates()) {
            return Verdict.of("THMP_EMPTY", model, tenantId, oldRoute, List.of(), null);
        }
        String oldBase = oldRoute.getBaseUrl();
        List<String> diffs = new ArrayList<>();
        ThmpContractClient.Candidate matched = null;
        for (ThmpContractClient.Candidate c : result.candidates()) {
            if (oldBase != null && oldBase.equals(c.upstream_base_url())) {
                matched = c;
                break;
            }
        }
        if (matched == null) {
            diffs.add("base_url");
            return Verdict.of("DIFF", model, tenantId, oldRoute,
                    basesOf(result), String.join(",", diffs));
        }
        String oldProto = oldRoute.getProtocol() == null ? null
                : oldRoute.getProtocol().toLowerCase(Locale.ROOT);
        String thmpProto = matched.protocol_type() == null ? null
                : matched.protocol_type().toLowerCase(Locale.ROOT);
        if (oldProto != null && thmpProto != null && !oldProto.equals(thmpProto)) {
            diffs.add("protocol:" + oldProto + "!=" + thmpProto);
        }
        return diffs.isEmpty()
                ? Verdict.of("MATCH", model, tenantId, oldRoute, basesOf(result), null)
                : Verdict.of("DIFF", model, tenantId, oldRoute, basesOf(result),
                        String.join(",", diffs));
    }

    private static List<String> basesOf(ThmpContractClient.ResolveResult result) {
        List<String> bases = new ArrayList<>();
        if (result.candidates() != null) {
            for (ThmpContractClient.Candidate c : result.candidates()) {
                bases.add(c.upstream_base_url());
            }
        }
        return bases;
    }

    /**
     * 租户归一: 数字透传 (预留 W3 映射), 空/非数字归 "0" (公共渠道).
     */
    static String normalizeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return "0";
        }
        try {
            Long.parseLong(tenantId.trim());
            return tenantId.trim();
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    /**
     * 比对结论 (logLine 即影子期唯一埋点输出 — W2 看板按行聚合).
     */
    public record Verdict(String result, String model, String tenantId, String oldChannelId,
                          String oldBaseUrl, List<String> thmpBaseUrls, String detail) {

        static Verdict of(String result, String model, String tenantId, DistributeVO oldRoute,
                          List<String> thmpBases, String detail) {
            return new Verdict(result, model, tenantId,
                    oldRoute == null ? null : oldRoute.getChannelId(),
                    oldRoute == null ? null : oldRoute.getBaseUrl(),
                    thmpBases, detail);
        }

        public String logLine() {
            return "result=" + result
                    + " model=" + model
                    + " tenant=" + tenantId
                    + " old_channel=" + oldChannelId
                    + " old_base=" + oldBaseUrl
                    + " thmp_bases=" + thmpBaseUrls
                    + (detail == null || detail.isBlank() ? "" : " diff=" + detail);
        }
    }
}
