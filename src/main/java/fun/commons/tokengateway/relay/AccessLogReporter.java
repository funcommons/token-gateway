package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.contract.AccessLogRequest;
import fun.commons.tokengateway.contract.OwnerType;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.rpc.HttpAccessLogApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 访问日志构造 + fire-and-forget 上报.
 *
 * <p>把字段收集 (traceId / tenantId / token usage / latency) 与 RPC 调用封装到一处,
 * 避免每个 Controller 重复; 同点代理渠道健康上报 (ChannelHealthReporter):
 * 成功 → record-success, 上游 5xx → record-failure, 客户端取消/4xx 不上报.
 *
 * <p>失败仅记日志, 不影响主响应.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogReporter {

    private final HttpAccessLogApi accessLogApi;
    private final ChannelHealthReporter channelHealthReporter;

    /**
     * fire-and-forget 上报访问日志 (上游成功路径).
     *
     * @param traceId 链路 ID (TraceWebFilter 物化传入); null 时生成 UUID
     */
    public Mono<Void> reportSuccess(RelayOrchestrator.PreparedRequest prepared,
                                    String model, String requestPath,
                                    int promptTokens, int completionTokens, int cachedTokens,
                                    java.math.BigDecimal creditConsumed,
                                    int latencyMs, String traceId) {
        return report(prepared, model, requestPath, 200,
                promptTokens, completionTokens, cachedTokens, creditConsumed, latencyMs, traceId);
    }

    /**
     * fire-and-forget 上报访问日志 (上游错误路径).
     *
     * @param traceId 链路 ID (TraceWebFilter 物化传入); null 时生成 UUID
     */
    public Mono<Void> reportError(RelayOrchestrator.PreparedRequest prepared,
                                  String model, String requestPath,
                                  int httpStatus, int latencyMs, String traceId) {
        return report(prepared, model, requestPath, httpStatus, 0, 0, 0, null, latencyMs, traceId);
    }

    private Mono<Void> report(RelayOrchestrator.PreparedRequest prepared,
                              String model, String requestPath, int statusCode,
                              int promptTokens, int completionTokens, int cachedTokens,
                              java.math.BigDecimal creditConsumed,
                              int latencyMs,
                              String traceId) {
        if (prepared == null) {
            return Mono.empty();
        }
        TokenValidateVO token = prepared.token();
        DistributeVO channel = prepared.channel();
        AccessLogRequest entity = AccessLogRequest.builder()
                .traceId(traceId != null ? traceId : UUID.randomUUID().toString())
                .tenantId(parseLong(token != null ? token.getTenantId() : null))
                .userId(parseLong(token != null ? token.getUserId() : null))
                .apiKeyId(parseLong(token != null ? token.getTokenId() : null))
                .channelId(parseLong(channel != null ? channel.getChannelId() : null))
                .modelCode(model)
                .requestMethod("POST")
                .requestPath(requestPath)
                .statusCode(statusCode)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .cachedTokens(cachedTokens)
                .billingMode(channel != null && channel.getOwnerType() != null
                        ? (channel.getOwnerType() == OwnerType.PLATFORM
                                ? "PLATFORM_DUAL" : "TENANT_SOLO")
                        : "PLATFORM_DUAL")
                .creditConsumed(creditConsumed)
                .latencyMs(latencyMs)
                .build();
        Mono<Void> health = reportChannelHealth(channel, token, statusCode);
        return accessLogApi.record(entity).then(health);
    }

    /** 渠道健康信号: 200 → record-success; 5xx → record-failure; 其余 (客户端取消/4xx) 不上报. */
    private Mono<Void> reportChannelHealth(DistributeVO channel, TokenValidateVO token, int statusCode) {
        if (statusCode == 200) {
            return channelHealthReporter.reportSuccess(channel);
        }
        if (statusCode >= 500) {
            return channelHealthReporter.reportFailure(channel,
                    token != null ? token.getTenantId() : null,
                    String.valueOf(statusCode), "upstream error");
        }
        return Mono.empty();
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
