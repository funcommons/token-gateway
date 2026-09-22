package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.contract.DistributeRequest;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.RecordFailureRequest;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * ChannelApi HTTP 实现 (RPC 调主应用 bootstrap 的 /api/v1/internal/channels/* 端点).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpChannelApi {

    private static final ParameterizedTypeReference<ApiResponse<DistributeVO>> DISTRIBUTE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final CapabilityEndpoints endpoints;
    private final RpcInternalAuth internalAuth;

    /**
     * LLM 面渠道路由: 打 route 面 chat 分发端点
     * ({@code token-gateway.route.distribute-path}, 默认 /api/v1/internal/channels/distribute).
     */
    public Mono<ApiResponse<DistributeVO>> distribute(DistributeRequest request) {
        return distribute(request, endpoints.route());
    }

    /**
     * 任务面渠道路由 (#12 work 域): 打 {@code token-gateway.task.distribute-path}
     * (默认 /api/v1/internal/work-channels/distribute).
     *
     * <p>回归 2026-09-21-04 BL11 P1-5: 与 LLM 面 {@link #distribute} 分离 — 两 face
     * 共用一个分发端点键曾使 LLM 本地渠道路由误打 work-channels 端点, 宿主按 workId
     * 解析 LLM 形状请求 (无 workId) 抛 {@code Long.parseLong(null)} → 502/10004.
     */
    public Mono<ApiResponse<DistributeVO>> distributeWork(DistributeRequest request) {
        return distribute(request, endpoints.taskRoute());
    }

    private Mono<ApiResponse<DistributeVO>> distribute(DistributeRequest request, EndpointConfig endpoint) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(endpoint.getUrl() + endpoint.getPath())
                .bodyValue(request);
        internalAuth.attachTo(req, endpoint);
        return req.retrieve()
                .bodyToMono(DISTRIBUTE_TYPE)
                .timeout(endpoint.getTimeout())
                .doOnError(e -> log.error("[HttpChannelApi] distribute RPC 失败: model={}, err={}",
                        request != null ? request.getModel() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "channel RPC failed: " + e.getMessage())));
    }

    public Mono<ApiResponse<Void>> recordFailure(String channelId, RecordFailureRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(endpoints.route().getUrl() + "/api/v1/internal/channels/{channelId}/record-failure", channelId)
                .bodyValue(request);
        internalAuth.attachTo(req, endpoints.route());
        return req.retrieve().bodyToMono(VOID_TYPE)
                .timeout(endpoints.route().getTimeout())
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "channel RPC failed: " + e.getMessage())));
    }

    public Mono<ApiResponse<Void>> recordSuccess(String channelId) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(endpoints.route().getUrl() + "/api/v1/internal/channels/{channelId}/record-success", channelId);
        internalAuth.attachTo(req, endpoints.route());
        return req.retrieve().bodyToMono(VOID_TYPE)
                .timeout(endpoints.route().getTimeout())
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "channel RPC failed: " + e.getMessage())));
    }
}
