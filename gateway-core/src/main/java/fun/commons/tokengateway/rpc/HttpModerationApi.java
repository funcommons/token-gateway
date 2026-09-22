package fun.commons.tokengateway.rpc;

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
 *
 * <p><b>enabled 双闸 (issue #38)</b>: {@code token-gateway.moderation.enabled=false}
 * (缺省) 时 {@link #scan} 与 {@link #audit} 均在本层短路, 不发任何 RPC ——
 * scan 直接 PASS_THROUGH 放行 (sanitizedContent 回显原文), audit 返回 {@code Mono.empty()}
 * (下游见到成功放行语义). 闸放在本类而非 {@code ModerationGate}: controller 的
 * auditOutput 链路绕过 Gate 直调本类, 只闸 Gate 会漏一半. enabled=true 时两条路径
 * 逐字节维持原行为 (fail-open/degrade 逻辑不动).
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
    private final CapabilityEndpoints endpoints;
    private final RpcInternalAuth internalAuth;
    private final fun.commons.tokengateway.spi.config.TokenGatewayProperties spi;

    /** 审核 degrade 规则码 (fail-closed 拦截时回给调用方的命中标识). */
    public static final String DEGRADE_RULE = "MODERATION_UNAVAILABLE";

    /**
     * 调审核面 /v1/internal/moderation/scan, 解析为 ModerationOutcome.
     *
     * <p>G6: fail-open/closed 由 {@code token-gateway.moderation.fail-open} 配置
     * (缺省 true = 现网口径; 仅 enabled=true 时有意义):
     * <ul>
     *   <li>fail-open: RPC 失败/业务 fail → PASS_THROUGH 放行 (可用性优先)</li>
     *   <li>fail-closed: 同类失败 → BLOCK (合规优先, 拦截不误放; 走既有 BLOCK 路径
     *       返回 10106, 不触发渠道轮换)</li>
     * </ul>
     *
     * <p>issue #38: {@code moderation.enabled=false} (缺省) 时短路, 不发 RPC,
     * 直接 PASS_THROUGH (sanitizedContent 回显原文, 与 fail-open 降级同形,
     * 上游 {@code maskedContentOf} 只认 MASK 动作, body 不会被改写).
     */
    public Mono<ModerationOutcome> scan(ScanRequest request) {
        if (!spi.getModeration().isEnabled()) {
            return Mono.just(ModerationOutcome.pass(request == null ? null : request.getContent()));
        }
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(endpoints.moderation().getUrl() + "/v1/internal/moderation/scan")
                .bodyValue(request);
        internalAuth.attachTo(req, endpoints.moderation());
        return req.retrieve()
                .bodyToMono(SCAN_TYPE)
                .timeout(endpoints.moderation().getTimeout())
                .map(this::toOutcome)
                .doOnError(e -> log.error("[HttpModerationApi] scan RPC 失败: tenantId={}, err={}",
                        request != null ? request.getTenantId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(degradedOutcome(
                        request == null ? null : request.getContent())));
    }

    private ModerationOutcome degradedOutcome(String content) {
        if (spi.getModeration().isFailOpen()) {
            return ModerationOutcome.pass(content);
        }
        log.warn("[HttpModerationApi] fail-closed 拦截: 审核面不可用, 按 BLOCK 处理");
        return ModerationOutcome.block(java.util.List.of(DEGRADE_RULE));
    }

    private ModerationOutcome toOutcome(ApiResponse<ScanResult> resp) {
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            log.warn("[HttpModerationApi] 审核面返回 fail/空: code={}, msg={}",
                    resp == null ? -1 : resp.getCode(),
                    resp == null ? "null" : resp.getMessage());
            return degradedOutcome(null);
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
     *
     * <p>issue #38: {@code moderation.enabled=false} (缺省) 时短路, 不发 RPC,
     * 返回 {@code Mono.empty()} —— 调用方 (controller auditOutput) 的 flatMap 不触发,
     * Mono<Void> 空完成即「成功放行」语义, 与内容为空/RPC 失败 fail-open 同口径.
     */
    public Mono<ApiResponse<ModerationAuditVO>> audit(ModerationAuditRequest request) {
        if (!spi.getModeration().isEnabled()) {
            return Mono.empty();
        }
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(endpoints.moderation().getUrl() + "/v1/internal/moderation/audit")
                .bodyValue(request);
        internalAuth.attachTo(req, endpoints.moderation());
        return req.retrieve()
                .bodyToMono(AUDIT_TYPE)
                .timeout(endpoints.moderation().getTimeout())
                .doOnError(e -> log.error("[HttpModerationApi] audit RPC 失败: err={}", e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "moderation audit RPC failed: " + e.getMessage())));
    }
}
