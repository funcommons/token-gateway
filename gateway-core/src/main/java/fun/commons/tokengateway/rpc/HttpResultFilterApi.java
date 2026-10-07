package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 终态结果过滤面 RPC (issue #43, 第 9 面): 任务面 SUCCEEDED 落库前回调消费方改写 result.
 *
 * <p>寻址 {@link CapabilityEndpoints#resultFilter()} (逐字段缺省回退 backend 平移值,
 * timeout 缺省 60s); 内部鉴权复用 {@link RpcInternalAuth} 面感知三式.
 * POST {url}{path} (path 缺省 {@code /v1/internal/tasks/result-filter}).
 *
 * <p>契约 (信封风格同现有能力面):
 * <ul>
 *   <li>请求: {@code {requestId, taskNo, tenantId, modality, model, status:"SUCCEEDED",
 *       result:{...原始 result (上游裸 URL)...}}}</li>
 *   <li>响应: {@code {code:0, data:{result:{...改写后...}}}}; {@code data:null} 或
 *       {@code data.result:null} = 不改写直通; code≠0 = 失败</li>
 * </ul>
 *
 * <p><b>消费方幂等义务</b>: 同一 taskNo 可能因失败重试/多实例并发被多次回调,
 * 消费方须按 taskNo 幂等 (覆盖写同键). requestId 取 taskNo (与任务面 settle 的
 * requestId 口径一致), 消费方日志/对账按它关联.
 *
 * <p>失败统一 onErrorResume 降级 fail 信封, 不向上抛 (同 HttpBillingApi 风格);
 * 重试/死信/fail-open 语义由调用方 (face-task TerminalResultFilter) 裁决.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpResultFilterApi {

    private static final ParameterizedTypeReference<ApiResponse<ResultFilterData>> TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final CapabilityEndpoints endpoints;
    private final RpcInternalAuth internalAuth;

    /** 回调消费方改写终态 result; RPC 失败/超时降级为 fail 信封 (code=10003). */
    public Mono<ApiResponse<ResultFilterData>> filter(ResultFilterRequest request) {
        EndpointConfig ep = endpoints.resultFilter();
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().post()
                .uri(ep.getUrl() + ep.getPath())
                .bodyValue(request);
        internalAuth.attachTo(req, ep);
        return req.retrieve()
                .bodyToMono(TYPE)
                .timeout(ep.getTimeout())
                .doOnError(e -> log.error("[HttpResultFilterApi] filter RPC 失败: taskNo={}, err={}",
                        request != null ? request.getTaskNo() : null, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(ApiCode.SERVICE_TIMEOUT.getCode(),
                        "result-filter RPC failed: " + e.getMessage())));
    }

    /**
     * 改写请求 (字段 camelCase 与信封契约一致). result = 上游原始终态 result
     * (含渠道裸 URL, 尚未经网关代理 URL 转换); status 恒 "SUCCEEDED" (只拦成功终态).
     * tenantId/model 取自 TaskMeta (create 时刻落账, #43 起填充; 历史任务为 null).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResultFilterRequest {
        /** 请求标识 (取 taskNo, 消费方日志/对账关联锚). */
        private String requestId;
        /** 网关任务号 (消费方幂等键: 多次回调覆盖写同键). */
        private String taskNo;
        private String tenantId;
        /** 任务模态 (实为 submit task_type: 默认模态, submit-task-type=model 时为模型编码). */
        private String modality;
        /** 请求模型编码 (create 时刻落账; 历史任务 null). */
        private String model;
        /** 恒 "SUCCEEDED" (FAILED/EXPIRED/CANCELLED 不过滤). */
        private String status;
        /** 原始终态 result (上游裸 URL). */
        private Map<String, Object> result;
    }

    /** 改写响应 data: {@code result=null} (或 data 整体 null) = 不改写直通. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResultFilterData {
        private Map<String, Object> result;
    }
}
