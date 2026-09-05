package fun.commons.tokengateway.rpc;

import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * token-route 路由面客户端 (G2/G5): resolve 选路 + report 三态回报.
 *
 * <p>adapter=tokengo | openapi 时 route 面由本客户端代替 Mmagix 的 distribute:
 * <pre>
 *   POST {route.url}/v1/resolve  {table_id, session_id?, biz_params?}
 *     → {entry_id, data_json, lease_id, ...}
 *   POST {route.url}/v1/report   {reports:[{entry_id, lease_id, result, rate_units, latency_ms}]}
 *     result ∈ SUCCESS | RETRYABLE_FAIL | DISABLE_FAIL
 * </pre>
 *
 * <p><b>data_json 契约</b> (部署方在 token-route 表 entry 里配置, 与 DistributeVO
 * 字段同名): channelId / baseUrl / apiKey / protocol / modelMapping — 网关按
 * OpenAI 兼容直通语义消费, 不感知 TokenGo 内部拓扑。
 *
 * <p>report 全部 fire-and-forget: 失败仅记日志, 不影响主链路 (路由面降级不阻断数据面).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenRouteClient {

    private static final ParameterizedTypeReference<ApiResponse<ResolveResponse>> RESOLVE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<ReportResponse>> REPORT_TYPE =
            new ParameterizedTypeReference<>() {};

    /** 三态回报 (token-route TR-CTR-002 §6). */
    public enum ReportResult {
        /** 尝试成功. */
        SUCCESS,
        /** 可重试失败 (轮换即回此态, 入口仍存活). */
        RETRYABLE_FAIL,
        /** 不可重试失败 (禁用语义: 供路由侧惩罚/摘除). */
        DISABLE_FAIL
    }

    private final WebClient.Builder webClientBuilder;
    private final CapabilityEndpoints endpoints;
    private final RpcInternalAuth internalAuth;
    private final TokenGatewayProperties spi;

    /** 选路结果: DistributeVO + token-route 凭据 (三态回报必需). */
    public record Resolved(DistributeVO channel, String entryId, String leaseId) {
    }

    /**
     * 选路 (全量凭据): model 作 biz_params 传入, tableId 取 {@code token-gateway.route.table-id}.
     *
     * @throws fun.commons.tokengateway.exception.RelayException resolve 空/失败 (502 语义)
     */
    public Mono<Resolved> resolveFull(String model, String sessionId, int estPromptTokens,
                                      int estCompletionTokens, List<String> excludeChannelIds) {
        EndpointConfig route = endpoints.route();
        JSONObject body = new JSONObject();
        body.put("table_id", spi.getRoute().getTableId());
        if (sessionId != null && !sessionId.isBlank()) {
            body.put("session_id", sessionId);
        }
        Map<String, Object> biz = new java.util.HashMap<>();
        biz.put("model", model);
        biz.put("est_prompt_tokens", estPromptTokens);
        biz.put("est_completion_tokens", estCompletionTokens);
        if (excludeChannelIds != null && !excludeChannelIds.isEmpty()) {
            biz.put("exclude_channel_ids", excludeChannelIds);
        }
        body.put("biz_params", biz);

        return webClientBuilder.build().post()
                .uri(route.getUrl() + "/v1/resolve")
                .bodyValue(body)
                .headers(h -> internalAuth.applyTo(h, route))
                .retrieve()
                .bodyToMono(RESOLVE_TYPE)
                .timeout(route.getTimeout())
                .flatMap(resp -> {
                    if (resp == null || !resp.isSuccess() || resp.getData() == null
                            || resp.getData().getEntryId() == null) {
                        String reason = resp == null || resp.getData() == null
                                || resp.getData().getReason() == null ? "no entry" : resp.getData().getReason();
                        return Mono.error(new fun.commons.tokengateway.exception.RelayException(
                                502, "token-route resolve 无可用候选: " + reason));
                    }
                    ResolveResponse r = resp.getData();
                    return Mono.just(new Resolved(toDistribute(r), r.getEntryId(), r.getLeaseId()));
                })
                .doOnError(e -> log.error("[TokenRoute] resolve 失败: model={}, err={}", model, e.getMessage()));
    }

    /**
     * 选路 (便捷形态): 只取 DistributeVO.
     */
    public Mono<DistributeVO> resolve(String model, String sessionId, int estPromptTokens,
                                      int estCompletionTokens, List<String> excludeChannelIds) {
        return resolveFull(model, sessionId, estPromptTokens, estCompletionTokens, excludeChannelIds)
                .map(Resolved::channel);
    }

    /**
     * 三态回报 (fire-and-forget): 失败仅记日志, 永不影响主链路.
     */
    public Mono<Void> report(String entryId, String leaseId, ReportResult result,
                             double rateUnits, int latencyMs) {
        EndpointConfig route = endpoints.route();
        JSONObject item = new JSONObject();
        item.put("entry_id", entryId);
        item.put("lease_id", leaseId);
        item.put("result", result.name());
        item.put("rate_units", rateUnits);
        item.put("latency_ms", latencyMs);
        JSONObject body = new JSONObject();
        body.put("reports", List.of(item));

        return webClientBuilder.build().post()
                .uri(route.getUrl() + "/v1/report")
                .bodyValue(body)
                .headers(h -> internalAuth.applyTo(h, route))
                .retrieve()
                .bodyToMono(REPORT_TYPE)
                .timeout(route.getTimeout())
                .doOnError(e -> log.warn("[TokenRoute] report 失败: entry={}, result={}, err={}",
                        entryId, result, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** data_json 契约字段 → DistributeVO (同名映射; 缺失字段走 DistributeVO 默认语义). */
    private DistributeVO toDistribute(ResolveResponse r) {
        Map<String, Object> d = r.dataJson == null ? Map.of() : r.dataJson;
        DistributeVO vo = new DistributeVO();
        vo.setChannelId(str(d.get("channelId"), r.entryId));
        vo.setBaseUrl(str(d.get("baseUrl"), null));
        vo.setApiKey(str(d.get("apiKey"), null));
        vo.setProtocol(str(d.get("protocol"), "openai"));
        if (d.get("modelMapping") instanceof Map<?, ?> mm) {
            Map<String, String> mapping = new java.util.HashMap<>();
            mm.forEach((k, v) -> mapping.put(String.valueOf(k), String.valueOf(v)));
            vo.setModelMapping(mapping);
        }
        return vo;
    }

    private static String str(Object v, String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    /** token-route resolve 响应 (snake_case 显式映射, Jackson 反序列化). */
    @lombok.Data
    static class ResolveResponse {
        @com.fasterxml.jackson.annotation.JsonProperty("entry_id")
        private String entryId;
        @com.fasterxml.jackson.annotation.JsonProperty("data_json")
        private Map<String, Object> dataJson;
        @com.fasterxml.jackson.annotation.JsonProperty("lease_id")
        private String leaseId;
        private String reason;
    }

    @lombok.Data
    static class ReportResponse {
        private Integer accepted;
        private List<Object> rejected;
    }
}
