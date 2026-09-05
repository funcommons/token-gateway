package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.contract.AccessLogRequest;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * AccessLog HTTP RPC (调主应用 /api/v1/internal/access-log/record).
 *
 * <p>fire-and-forget: Controller 在 settle/refund 后调用, 失败仅记日志,
 * 不向上抛异常 (访问日志丢失不影响主业务).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpAccessLogApi {

    private static final ParameterizedTypeReference<ApiResponse<Void>> TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final CapabilityEndpoints endpoints;
    private final RpcInternalAuth internalAuth;

    public Mono<Void> record(AccessLogRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(endpoints.accessLog().getUrl() + "/api/v1/internal/access-log/record")
                .bodyValue(request);
        internalAuth.attachTo(req, endpoints.accessLog());
        return req.retrieve()
                .bodyToMono(TYPE)
                .timeout(endpoints.accessLog().getTimeout())
                .doOnError(e -> log.warn("[HttpAccessLog] record RPC 失败: model={}, err={}",
                        request != null ? request.getModelCode() : null, e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
