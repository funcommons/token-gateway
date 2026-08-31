package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.DistributeRequest;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.RecordFailureRequest;
import fun.commons.tokengateway.framework.ApiResponse;
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
    private final GatewayProperties props;
    private final RpcInternalAuth internalAuth;

    public Mono<ApiResponse<DistributeVO>> distribute(DistributeRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/api/v1/internal/channels/distribute")
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve()
                .bodyToMono(DISTRIBUTE_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpChannelApi] distribute RPC 失败: model={}, err={}",
                        request != null ? request.getModel() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "channel RPC failed: " + e.getMessage())));
    }

    public Mono<ApiResponse<Void>> recordFailure(String channelId, RecordFailureRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/api/v1/internal/channels/{channelId}/record-failure", channelId)
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve().bodyToMono(VOID_TYPE)
                .timeout(props.getTimeout())
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "channel RPC failed: " + e.getMessage())));
    }

    public Mono<ApiResponse<Void>> recordSuccess(String channelId) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/api/v1/internal/channels/{channelId}/record-success", channelId);
        internalAuth.attachTo(req);
        return req.retrieve().bodyToMono(VOID_TYPE)
                .timeout(props.getTimeout())
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "channel RPC failed: " + e.getMessage())));
    }
}
