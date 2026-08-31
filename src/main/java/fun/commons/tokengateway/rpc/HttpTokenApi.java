package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * TokenApi HTTP 实现 (RPC 调主应用 bootstrap 的 /api/v1/internal/tokens/* 端点).
 *
 * <p>替代单体模式下的本地 bean 注入. 走 WebClient + 内部 token 鉴权.
 *
 * <p>P99 延迟目标: &lt; 30ms (本地 loopback). 后续可加 Caffeine 缓存降到接近 0.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpTokenApi {

    private static final ParameterizedTypeReference<ApiResponse<TokenValidateVO>> VALIDATE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final GatewayProperties props;
    private final RpcInternalAuth internalAuth;

    /**
     * 校验 API Key (走主应用 TokenApi 端点).
     *
     * @return 主应用返回的 ApiResponse; RPC 失败 (网络/超时) 返回 fail 包络, code=10003.
     */
    public Mono<ApiResponse<TokenValidateVO>> validate(TokenValidateRequest request) {
        WebClient.RequestHeadersSpec<?> req =
                webClientBuilder.build().post()
                        .uri(props.getUrl() + "/api/v1/internal/tokens/validate")
                        .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve()
                .bodyToMono(VALIDATE_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpTokenApi] validate RPC 失败: apiKey={}, err={}",
                        request != null ? request.getApiKey() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "token RPC failed: " + e.getMessage())));
    }
}
