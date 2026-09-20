package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.config.HealthReportProperties;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.RecordFailureRequest;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 渠道健康信号上报 (record-success / record-failure), 由 AccessLogReporter 在
 * 请求完结点统一代理调用, fire-and-forget 不阻塞主链路.
 *
 * <p>语义 (对齐设计方案 §5.3 health-report 开关; issue #25 定版口径):
 * <ul>
 *   <li>上游成功 (status 200) → record-success</li>
 *   <li>上游故障 (4xx/5xx, 含 200+错误载荷软失败) → record-failure
 *       (errorCode=HTTP_&lt;status&gt;, 附 upstreamStatus/latencyMs 维度)</li>
 *   <li>客户端取消 (499) → 不上报 (非渠道责任)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelHealthReporter {

    /**
     * 499 客户端取消: 当前不上报 (499 不产生 record-failure, 见 AccessLogReporter);
     * 未来如需上报, 能力面按 CANCELLED 单列且不计失败 (取消非渠道之错, 计失败会误熔断).
     */
    public static final String ERROR_CODE_CANCELLED = "CANCELLED";

    private final HttpChannelApi channelApi;
    private final HealthReportProperties props;

    public Mono<Void> reportSuccess(DistributeVO channel) {
        if (!props.isEnabled() || channel == null || channel.getChannelId() == null) {
            return Mono.empty();
        }
        return channelApi.recordSuccess(channel.getChannelId())
                .doOnError(e -> log.warn("[ChannelHealth] record-success 失败: channel={}, err={}",
                        channel.getChannelId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    public Mono<Void> reportFailure(DistributeVO channel, String tenantId,
                                    String errorCode, String errorMessage) {
        return reportFailure(channel, tenantId, errorCode, errorMessage, null, null);
    }

    /**
     * issue #25: 6 参重载, 附上游真实状态码与耗时维度 (向后兼容可选字段,
     * 旧能力面忽略未知字段零影响).
     */
    public Mono<Void> reportFailure(DistributeVO channel, String tenantId,
                                    String errorCode, String errorMessage,
                                    Integer upstreamStatus, Long latencyMs) {
        if (!props.isEnabled() || channel == null || channel.getChannelId() == null) {
            return Mono.empty();
        }
        return channelApi.recordFailure(channel.getChannelId(), RecordFailureRequest.builder()
                        .tenantId(tenantId)
                        .errorCode(errorCode)
                        .errorMessage(errorMessage)
                        .upstreamStatus(upstreamStatus)
                        .latencyMs(latencyMs)
                        .build())
                .doOnError(e -> log.warn("[ChannelHealth] record-failure 失败: channel={}, err={}",
                        channel.getChannelId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
