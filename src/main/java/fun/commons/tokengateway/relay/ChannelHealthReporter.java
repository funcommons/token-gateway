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
 * <p>语义 (对齐设计方案 §5.3 health-report 开关):
 * <ul>
 *   <li>上游成功 (status 200) → record-success</li>
 *   <li>上游故障 (status 5xx) → record-failure (带 errorCode/errorMessage)</li>
 *   <li>客户端取消/4xx → 不上报 (非渠道责任)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChannelHealthReporter {

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
        if (!props.isEnabled() || channel == null || channel.getChannelId() == null) {
            return Mono.empty();
        }
        return channelApi.recordFailure(channel.getChannelId(), RecordFailureRequest.builder()
                        .tenantId(tenantId)
                        .errorCode(errorCode)
                        .errorMessage(errorMessage)
                        .build())
                .doOnError(e -> log.warn("[ChannelHealth] record-failure 失败: channel={}, err={}",
                        channel.getChannelId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
