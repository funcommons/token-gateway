package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.ModerationAuditRequest;
import fun.commons.tokengateway.contract.ModerationAuditVO;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.moderation.ModerationOutcome;
import fun.commons.tokengateway.moderation.ScanRequest;
import fun.commons.tokengateway.moderation.ScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Moderation HTTP RPC (调主应用 /v1/internal/moderation/scan).
 *
 * <p>gateway-webflux 不直接依赖 moderation jar (避免 servlet 类加载冲突),
 * 走 WebClient + RpcInternalAuth HMAC 头. 返回 {@link ModerationOutcome} 三态封装.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpModerationApi {

    private static final ParameterizedTypeReference<ApiResponse<ScanResult>> SCAN_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<ModerationAuditVO>> AUDIT_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final GatewayProperties props;
    private final RpcInternalAuth internalAuth;

    /**
     * 调主应用 /v1/internal/moderation/scan, 解析为 ModerationOutcome.
     *
     * <p>RPC 失败 (网络/超时/5xx) → 返回 fail-open PASS_THROUGH (不阻断主流程).
     * <p>主应用业务级 fail (如 4xx 校验) → 同上降级, 记 error 日志.
     */
    public Mono<ModerationOutcome> scan(ScanRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/v1/internal/moderation/scan")
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve()
                .bodyToMono(SCAN_TYPE)
                .timeout(props.getTimeout())
                .map(this::toOutcome)
                .doOnError(e -> log.error("[HttpModerationApi] scan RPC 失败: tenantId={}, err={}",
                        request != null ? request.getTenantId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ModerationOutcome.pass(
                        request == null ? null : request.getContent())));
    }

    private ModerationOutcome toOutcome(ApiResponse<ScanResult> resp) {
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            log.warn("[HttpModerationApi] 主应用返回 fail/空: code={}, msg={}",
                    resp == null ? -1 : resp.getCode(),
                    resp == null ? "null" : resp.getMessage());
            return ModerationOutcome.pass(null);
        }
        ScanResult result = resp.getData();
        String action = result.getActionTaken();
        if ("BLOCK".equalsIgnoreCase(action)) {
            var codes = result.getMatches() == null ? java.util.List.<String>of()
                    : result.getMatches().stream().map(ScanResult.Match::getRuleCode).toList();
            return ModerationOutcome.block(codes);
        }
        if ("MASK".equalsIgnoreCase(action)) {
            return ModerationOutcome.mask(result.getSanitizedContent());
        }
        return ModerationOutcome.pass(result.getSanitizedContent());
    }

    /**
     * 输出审查 (调主应用 /v1/internal/moderation/audit).
     * <p>RPC 失败返回 fail 包络, 不抛异常 (调用方决定 fail-open).
     */
    public Mono<ApiResponse<ModerationAuditVO>> audit(ModerationAuditRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/v1/internal/moderation/audit")
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve()
                .bodyToMono(AUDIT_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpModerationApi] audit RPC 失败: err={}", e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "moderation audit RPC failed: " + e.getMessage())));
    }
}
