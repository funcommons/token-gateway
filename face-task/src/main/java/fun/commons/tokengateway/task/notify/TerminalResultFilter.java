package fun.commons.tokengateway.task.notify;

import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.rpc.HttpResultFilterApi;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * 终态结果过滤器 (issue #43): SUCCEEDED 落库前回调<b>消费方</b>改写 result
 * (写时过滤, 一次改写; 消费方转存自家 OSS/审核/脱敏后, 网关存储与对外只见改写后值).
 *
 * <p>语义钉死 (与维护者逐条确认):
 * <ul>
 *   <li>只拦 SUCCEEDED; FAILED/EXPIRED/CANCELLED 不走本类</li>
 *   <li>改写作用于<b>原始 result</b> (上游裸 URL), 改写后值才进代理 URL 转换 → 落库 → notify</li>
 *   <li>开关 {@code token-gateway.result-filter.enabled} 默认关 —— 关时
 *       {@link TerminalEventHandler} 不调用本类任何方法 (回归红线)</li>
 *   <li>消费方幂等义务: 同一 taskNo 可能因重试/多实例并发被多次回调, 覆盖写同键</li>
 * </ul>
 *
 * <p>失败语义 (骑既有终态机械): RPC 失败/超时/信封 code≠0 → 不落终态条目
 * (任务维持非终态, 对账/超时钟重放终态事件自然重试); Redis
 * {@code tgw:result-filter:attempts:{taskNo}} INCR 计数, 超过 max-attempts
 * (默认 5) → {@code [ResultFilter-DeadLetter]} 死信 + fail-open 落原值.
 * settle 与改写解耦: 改写失败/重试期间 settle 照常执行 (上游已交付=该收钱).
 */
@Slf4j
@Component
public class TerminalResultFilter {

    private final HttpResultFilterApi filterApi;
    private final TaskMetaStore metaStore;
    private final TokenGatewayProperties props;

    @Autowired
    public TerminalResultFilter(HttpResultFilterApi filterApi, TaskMetaStore metaStore,
                                TokenGatewayProperties props) {
        this.filterApi = filterApi;
        this.metaStore = metaStore;
        this.props = props;
    }

    private TerminalResultFilter(TokenGatewayProperties props) {
        this.filterApi = null;
        this.metaStore = null;
        this.props = props;
    }

    /**
     * 默认关闭实例 (TerminalEventHandler 兼容构造用 —— 测试/存量装配零破坏):
     * {@link #isEnabled()} 恒 false, filter() 永不被调用 (filterApi/metaStore 为 null 安全).
     */
    public static TerminalResultFilter disabled() {
        return new TerminalResultFilter(new TokenGatewayProperties());
    }

    /** 开关 (默认关): 关时终态链一行新逻辑不执行. */
    public boolean isEnabled() {
        return props.getResultFilter().isEnabled();
    }

    /**
     * SUCCEEDED 终态 result 改写判定.
     *
     * @param metaTtl task meta TTL (attempts 计数键 TTL 对齐它)
     * @return outcome: proceed(改写后值|原值直通|fail-open 原值) 继续终态链;
     *         retryLater = 不落终态 (维持非终态待重放)
     */
    public Mono<FilterOutcome> filter(String taskNo, TaskMeta meta, Map<String, Object> result,
                                      Duration metaTtl) {
        int maxAttempts = Math.max(1, props.getResultFilter().getMaxAttempts());
        HttpResultFilterApi.ResultFilterRequest request = HttpResultFilterApi.ResultFilterRequest.builder()
                .requestId(taskNo)
                .taskNo(taskNo)
                .tenantId(meta.tenantId())
                .modality(meta.modality())
                .model(meta.model())
                .status("SUCCEEDED")
                .result(result)
                .build();
        return filterApi.filter(request)
                .flatMap(resp -> {
                    if (resp != null && resp.isSuccess()) {
                        Map<String, Object> rewritten = resp.getData() == null
                                ? null : resp.getData().getResult();
                        if (rewritten == null) {
                            // data:null / data.result:null = 不改写直通
                            return Mono.just(FilterOutcome.proceed(result));
                        }
                        log.info("[ResultFilter] 消费方已改写终态 result: taskNo={}", taskNo);
                        return Mono.just(FilterOutcome.proceed(rewritten));
                    }
                    return onFailure(taskNo, result, metaTtl, maxAttempts, failReason(resp));
                })
                // 2xx 空 body 等畸形响应: 视同失败走重试语义 (defer 延迟装配, 避免误先计一次数)
                .switchIfEmpty(Mono.defer(() -> onFailure(taskNo, result, metaTtl, maxAttempts,
                        "empty response body")));
    }

    /** 失败分支: attempts INCR → 超限死信 + fail-open 原值; 未超限 retryLater (不落终态). */
    private Mono<FilterOutcome> onFailure(String taskNo, Map<String, Object> result,
                                          Duration metaTtl, int maxAttempts, String reason) {
        return metaStore.incrResultFilterAttempts(taskNo, metaTtl)
                .map(attempts -> {
                    if (attempts > maxAttempts) {
                        ResultFilterDeadLetters.log(taskNo, result, attempts, reason);
                        log.error("[ResultFilter] 改写重试耗尽 ({}>{}), fail-open 落原值: taskNo={}",
                                attempts, maxAttempts, taskNo);
                        return FilterOutcome.proceed(result);
                    }
                    log.warn("[ResultFilter] 改写失败, 不落终态待重放重试 ({}/{}): taskNo={}, reason={}",
                            attempts, maxAttempts, taskNo, reason);
                    return FilterOutcome.retryLater();
                });
    }

    private static String failReason(ApiResponse<?> resp) {
        if (resp == null) {
            return "null envelope";
        }
        return "code=" + resp.getCode() + ", message=" + resp.getMessage();
    }

    /**
     * 改写判定结果: {@code proceed=true} → 终态链继续 (result 为生效值: 改写后 |
     * 直通原值 | fail-open 原值); {@code proceed=false} → 不落终态条目
     * (任务维持非终态, 对账/超时钟重放终态事件自然重试; settle 由调用方照常执行).
     */
    public record FilterOutcome(boolean proceed, Map<String, Object> result) {

        static FilterOutcome proceed(Map<String, Object> result) {
            return new FilterOutcome(true, result);
        }

        static FilterOutcome retryLater() {
            return new FilterOutcome(false, null);
        }
    }
}
