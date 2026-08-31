package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.contract.SettleVO;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * BillingApi HTTP 实现 (调主应用 /api/v1/internal/billing/* 端点).
 *
 * <p>Saga 流程: preConsume (预扣) → 上游调用 → settle (结算) / refund (退款).
 * <p>所有 RPC 失败统一 onErrorResume 降级, 不向上抛异常 (避免阻塞请求).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpBillingApi {

    private static final ParameterizedTypeReference<ApiResponse<PreConsumeVO>> PRE_CONSUME_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<ApiResponse<SettleVO>> SETTLE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final GatewayProperties props;
    private final RpcInternalAuth internalAuth;

    public Mono<ApiResponse<PreConsumeVO>> preConsume(PreConsumeRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/api/v1/internal/billing/pre-consume")
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve()
                .bodyToMono(PRE_CONSUME_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpBillingApi] preConsume RPC 失败: userId={}, err={}",
                        request != null ? request.getUserId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing preConsume RPC failed: " + e.getMessage())));
    }

    public Mono<ApiResponse<SettleVO>> settle(SettleRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/api/v1/internal/billing/settle")
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve().bodyToMono(SETTLE_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpBillingApi] settle RPC 失败: preConsumeId={}, err={}",
                        request != null ? request.getPreConsumeId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing settle RPC failed: " + e.getMessage())));
    }

    public Mono<ApiResponse<Void>> refund(RefundRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(props.getUrl() + "/api/v1/internal/billing/refund")
                .bodyValue(request);
        internalAuth.attachTo(req);
        return req.retrieve().bodyToMono(VOID_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpBillingApi] refund RPC 失败: preConsumeId={}, err={}",
                        request != null ? request.getPreConsumeId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing refund RPC failed: " + e.getMessage())));
    }
}
