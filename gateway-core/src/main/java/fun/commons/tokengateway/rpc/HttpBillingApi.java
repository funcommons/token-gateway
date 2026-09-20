package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.contract.SettleVO;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * BillingApi HTTP 实现 (调主应用计费三端点, 路径前缀可配 — issue #14;
 * 默认 /api/v1/internal/billing, 任务面接入方可指 /v1/internal/billing/task).
 *
 * <p><b>面归属 (issue #31)</b>: 单类双面方法族, 寻址在调用方决定 —
 * <ul>
 *   <li>{@code preConsume/settle/refund} (通用族) → {@link CapabilityEndpoints#billing()}
 *       通用前缀: LLM 面 RelayOrchestrator / BillingReconcileJob (#23 重放兜底) 消费,
 *       token 估价 + settle 四维重算退差契约;</li>
 *   <li>{@code preConsumeTask/settleTask/refundTask} (任务族) →
 *       {@link CapabilityEndpoints#taskBilling()} (逐字段缺省回退通用前缀):
 *       仅 face-task TaskBillingSaga 消费, 全额 amount 直传 + settle 确认制契约.</li>
 * </ul>
 * 两面契约互斥, 不得混用; 未配 task.billing 时任务族路径与通用族一致 (回归红线).
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
    private final CapabilityEndpoints endpoints;
    private final RpcInternalAuth internalAuth;

    /** 通用族 preConsume (LLM 面): billing() 通用前缀 (issue #31). */
    public Mono<ApiResponse<PreConsumeVO>> preConsume(PreConsumeRequest request) {
        return doPreConsume(endpoints.billing(), request);
    }

    /** 任务族 preConsume (face-task): taskBilling() 寻址, 缺省回退通用前缀 (issue #31). */
    public Mono<ApiResponse<PreConsumeVO>> preConsumeTask(PreConsumeRequest request) {
        return doPreConsume(endpoints.taskBilling(), request);
    }

    /** 通用族 settle (LLM 面): billing() 通用前缀 (issue #31). */
    public Mono<ApiResponse<SettleVO>> settle(SettleRequest request) {
        return doSettle(endpoints.billing(), request);
    }

    /** 任务族 settle (face-task): taskBilling() 寻址, 缺省回退通用前缀 (issue #31). */
    public Mono<ApiResponse<SettleVO>> settleTask(SettleRequest request) {
        return doSettle(endpoints.taskBilling(), request);
    }

    /** 通用族 refund (LLM 面): billing() 通用前缀 (issue #31). */
    public Mono<ApiResponse<Void>> refund(RefundRequest request) {
        return doRefund(endpoints.billing(), request);
    }

    /** 任务族 refund (face-task): taskBilling() 寻址, 缺省回退通用前缀 (issue #31). */
    public Mono<ApiResponse<Void>> refundTask(RefundRequest request) {
        return doRefund(endpoints.taskBilling(), request);
    }

    private Mono<ApiResponse<PreConsumeVO>> doPreConsume(EndpointConfig ep,
                                                         PreConsumeRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(ep.getUrl() + ep.getPath() + "/pre-consume")
                .bodyValue(request);
        internalAuth.attachTo(req, ep);
        return req.retrieve()
                .bodyToMono(PRE_CONSUME_TYPE)
                .timeout(ep.getTimeout())
                .doOnError(e -> log.error("[HttpBillingApi] preConsume RPC 失败: userId={}, err={}",
                        request != null ? request.getUserId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing preConsume RPC failed: " + e.getMessage())));
    }

    private Mono<ApiResponse<SettleVO>> doSettle(EndpointConfig ep, SettleRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(ep.getUrl() + ep.getPath() + "/settle")
                .bodyValue(request);
        internalAuth.attachTo(req, ep);
        return req.retrieve().bodyToMono(SETTLE_TYPE)
                .timeout(ep.getTimeout())
                .doOnError(e -> log.error("[HttpBillingApi] settle RPC 失败: preConsumeId={}, err={}",
                        request != null ? request.getPreConsumeId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing settle RPC failed: " + e.getMessage())));
    }

    private Mono<ApiResponse<Void>> doRefund(EndpointConfig ep, RefundRequest request) {
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(ep.getUrl() + ep.getPath() + "/refund")
                .bodyValue(request);
        internalAuth.attachTo(req, ep);
        return req.retrieve().bodyToMono(VOID_TYPE)
                .timeout(ep.getTimeout())
                .doOnError(e -> log.error("[HttpBillingApi] refund RPC 失败: preConsumeId={}, err={}",
                        request != null ? request.getPreConsumeId() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "billing refund RPC failed: " + e.getMessage())));
    }
}
