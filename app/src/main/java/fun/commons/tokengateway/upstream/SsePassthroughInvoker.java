package fun.commons.tokengateway.upstream;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.format.SseTransformer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * SSE 流式透传调用上游 (从 backend/gateway 复制).
 *
 * <p>WebFlux 原生, 直接返 Flux<ServerSentEvent>, 不需要 servlet SseEmitter 桥接.
 *
 * <p>关键点: 上游字节流按 netty chunk 切, 不会因 {@code data: [DONE]} 自动结束,
 * 必须按 {@code \n\n} 重组完整帧, 收到 [DONE] 立即 complete.
 */
@Slf4j
@Component
public class SsePassthroughInvoker {

    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    public static final String HEARTBEAT = ": ping\n\n";

    private final WebClient.Builder webClientBuilder;
    private final Duration heartbeatInterval;

    @org.springframework.beans.factory.annotation.Autowired
    public SsePassthroughInvoker(WebClient.Builder webClientBuilder) {
        this(webClientBuilder, HEARTBEAT_INTERVAL);
    }

    SsePassthroughInvoker(WebClient.Builder webClientBuilder, Duration heartbeatInterval) {
        this.webClientBuilder = webClientBuilder;
        this.heartbeatInterval = heartbeatInterval;
    }

    public Flux<ServerSentEvent<String>> invokeStream(DistributeVO channel, Map<String, Object> body) {
        return invokeStream(channel, body, (java.util.function.Consumer<String>) null);
    }

    /**
     * @param frameConsumer 原始帧消费者 (usage 提取等), 可为 null
     */
    public Flux<ServerSentEvent<String>> invokeStream(DistributeVO channel, Map<String, Object> body,
                                                      java.util.function.Consumer<String> frameConsumer) {
        if (channel == null || channel.getBaseUrl() == null) {
            return Flux.error(new IllegalArgumentException("missing channel/baseUrl"));
        }
        String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
        log.info("[SsePassthrough] 启动: url={}, model={}", url, body.get("model"));

        Flux<String> raw = webClientBuilder.build().post()
                .uri(url)
                .header("Authorization", "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(SsePassthroughInvoker::drainToString)
                .doOnError(e -> logUpstreamError("", url, e))
                .doOnComplete(() -> log.info("[SsePassthrough] 上游关闭: url={}", url));

        Flux<ServerSentEvent<String>> events = parseSseFrames(raw)
                .doOnNext(frame -> acceptQuietly(frameConsumer, frame))
                .takeUntil(SsePassthroughInvoker::isDoneFrame)
                .flatMap(SsePassthroughInvoker::toServerSentEvent);

        return withHeartbeat(events);
    }

    public Flux<ServerSentEvent<String>> invokeStream(DistributeVO channel,
                                                      Map<String, Object> body,
                                                      SseTransformer transformer) {
        return invokeStream(channel, body, transformer, null);
    }

    public Flux<ServerSentEvent<String>> invokeStream(DistributeVO channel,
                                                      Map<String, Object> body,
                                                      SseTransformer transformer,
                                                      java.util.function.Consumer<String> frameConsumer) {
        if (channel == null || channel.getBaseUrl() == null) {
            return Flux.error(new IllegalArgumentException("missing channel/baseUrl"));
        }
        String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
        log.info("[SsePassthrough] 启动(transformed): url={}, model={}", url, body.get("model"));

        Flux<String> raw = webClientBuilder.build().post()
                .uri(url)
                .header("Authorization", "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(SsePassthroughInvoker::drainToString)
                .doOnError(e -> logUpstreamError("(transformed)", url, e))
                .doOnComplete(() -> log.info("[SsePassthrough] 上游关闭(transformed): url={}", url));

        return withHeartbeat(transformFrames(raw, transformer, frameConsumer));
    }

    public Flux<ServerSentEvent<String>> invokeStreamAnthropic(DistributeVO channel,
                                                               Map<String, Object> anthropicBody,
                                                               SseTransformer transformer) {
        return invokeStreamAnthropic(channel, anthropicBody, transformer, null);
    }

    public Flux<ServerSentEvent<String>> invokeStreamAnthropic(DistributeVO channel,
                                                               Map<String, Object> anthropicBody,
                                                               SseTransformer transformer,
                                                               java.util.function.Consumer<String> frameConsumer) {
        if (channel == null || channel.getBaseUrl() == null) {
            return Flux.error(new IllegalArgumentException("missing channel/baseUrl"));
        }
        String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/messages";
        log.info("[SsePassthrough] 启动(anthropic): url={}, model={}", url, anthropicBody.get("model"));

        Flux<String> raw = webClientBuilder.build().post()
                .uri(url)
                .header("x-api-key", channel.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .bodyValue(anthropicBody)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(SsePassthroughInvoker::drainToString)
                .doOnError(e -> logUpstreamError("(anthropic)", url, e))
                .doOnComplete(() -> log.info("[SsePassthrough] 上游关闭(anthropic): url={}", url));

        return withHeartbeat(transformFrames(raw, transformer, frameConsumer));
    }

    public Flux<ServerSentEvent<String>> invokeStreamAnthropicNative(DistributeVO channel,
                                                                     Map<String, Object> anthropicBody) {
        return invokeStreamAnthropicNative(channel, anthropicBody, null);
    }

    public Flux<ServerSentEvent<String>> invokeStreamAnthropicNative(DistributeVO channel,
                                                                     Map<String, Object> anthropicBody,
                                                                     java.util.function.Consumer<String> frameConsumer) {
        if (channel == null || channel.getBaseUrl() == null) {
            return Flux.error(new IllegalArgumentException("missing channel/baseUrl"));
        }
        String url = channel.getBaseUrl().replaceAll("/+$", "") + "/v1/messages";
        log.info("[SsePassthrough] 启动(anthropic-native): url={}, model={}",
                url, anthropicBody.get("model"));

        Flux<String> raw = webClientBuilder.build().post()
                .uri(url)
                .header("x-api-key", channel.getApiKey())
                .header("anthropic-version", "2023-06-01")
                .header("Accept", MediaType.TEXT_EVENT_STREAM_VALUE)
                .bodyValue(anthropicBody)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .map(SsePassthroughInvoker::drainToString)
                .doOnError(e -> logUpstreamError("(anthropic-native)", url, e, anthropicBody))
                .doOnComplete(() -> log.info("[SsePassthrough] 上游关闭(anthropic-native): url={}", url));

        Flux<ServerSentEvent<String>> events = parseSseFrames(raw)
                .doOnNext(frame -> acceptQuietly(frameConsumer, frame))
                .flatMap(SsePassthroughInvoker::toServerSentEventPreservingEvent);
        return withHeartbeat(events);
    }

    /**
     * 上游错误日志: WebClientResponseException 附带 status + 上游响应体 (400 排查关键).
     */
    private static void logUpstreamError(String tag, String url, Throwable e) {
        logUpstreamError(tag, url, e, null);
    }

    /**
     * 上游错误日志 (带出站请求体截断快照, 用于复现 400 的原始报文).
     */
    private static void logUpstreamError(String tag, String url, Throwable e, Map<String, Object> requestBody) {
        if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException wce) {
            log.error("[SsePassthrough] 上游异常{}: url={}, status={}, body={}, request={}",
                    tag, url, wce.getStatusCode(), wce.getResponseBodyAsString(), truncateBody(requestBody));
        } else {
            log.error("[SsePassthrough] 上游异常{}: url={}, request={}", tag, url, truncateBody(requestBody), e);
        }
    }

    private static String truncateBody(Map<String, Object> body) {
        if (body == null) {
            return "-";
        }
        try {
            return skeleton(body);
        } catch (Exception ex) {
            return String.valueOf(body);
        }
    }

    /**
     * 消息骨架: 每条消息 role + 内容块类型 (tool_use#id / tool_result->id), 定位 2013 排序问题.
     */
    @SuppressWarnings("unchecked")
    private static String skeleton(Map<String, Object> body) {
        StringBuilder sb = new StringBuilder();
        Object messages = body.get("messages");
        if (messages instanceof java.util.List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                if (!(list.get(i) instanceof Map<?, ?> msg)) continue;
                if (sb.length() > 0) sb.append(" | ");
                sb.append(i).append(':').append(msg.get("role")).append('[');
                Object content = msg.get("content");
                if (content instanceof java.util.List<?> blocks) {
                    StringBuilder blocksSb = new StringBuilder();
                    for (Object b : blocks) {
                        if (!(b instanceof Map<?, ?> block)) continue;
                        if (blocksSb.length() > 0) blocksSb.append(',');
                        Object type = block.get("type");
                        blocksSb.append(type);
                        if ("tool_use".equals(type)) {
                            blocksSb.append('#').append(block.get("id"));
                        } else if ("tool_result".equals(type)) {
                            blocksSb.append("->").append(block.get("tool_use_id"));
                        }
                    }
                    sb.append(blocksSb);
                } else {
                    sb.append(content instanceof String ? "text" : content);
                }
                sb.append(']');
            }
        }
        // 顶层关键字段 (影响上游校验的非 messages 参数)
        sb.append(" || keys=").append(body.keySet());
        if (body.containsKey("tools")) {
            Object tools = body.get("tools");
            sb.append(" tools=").append(tools instanceof java.util.List<?> l ? l.size() : tools);
        }
        return sb.toString();
    }

    private static void acceptQuietly(java.util.function.Consumer<String> consumer, String frame) {
        if (consumer == null) {
            return;
        }
        try {
            consumer.accept(frame);
        } catch (Exception e) {
            log.warn("[SsePassthrough] 帧消费者异常, 忽略: {}", e.toString());
        }
    }

    private Flux<ServerSentEvent<String>> transformFrames(Flux<String> raw, SseTransformer transformer) {
        return transformFrames(raw, transformer, null);
    }

    private Flux<ServerSentEvent<String>> transformFrames(Flux<String> raw, SseTransformer transformer,
                                                          java.util.function.Consumer<String> frameConsumer) {
        return parseSseFrames(raw)
                .doOnNext(frame -> acceptQuietly(frameConsumer, frame))
                .takeUntil(SsePassthroughInvoker::isDoneFrame)
                .flatMap(frame -> {
                    if (isDoneFrame(frame)) {
                        return Flux.<ServerSentEvent<String>>empty();
                    }
                    String data = extractDataPayload(frame);
                    if (data == null || data.isBlank()) {
                        return Flux.<ServerSentEvent<String>>empty();
                    }
                    Map<String, Object> chunkMap;
                    try {
                        chunkMap = com.alibaba.fastjson2.JSON.parseObject(data, Map.class);
                    } catch (Exception e) {
                        log.warn("[SsePassthrough] chunk 解析失败, 跳过: {}", data);
                        return Flux.<ServerSentEvent<String>>empty();
                    }
                    List<ServerSentEvent<String>> transformed = transformer.transform(chunkMap);
                    return Flux.fromIterable(transformed);
                })
                .concatWith(Flux.defer(() -> Flux.fromIterable(transformer.onComplete())));
    }

    static Flux<ServerSentEvent<String>> toServerSentEventPreservingEvent(String frame) {
        StringBuilder data = new StringBuilder();
        String event = null;
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                String payload = line.substring(5);
                data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
            } else if (line.startsWith("event:")) {
                String name = line.substring(6);
                event = name.startsWith(" ") ? name.substring(1) : name;
                event = event.trim();
            }
        }
        if (data.length() == 0) {
            return Flux.empty();
        }
        ServerSentEvent.Builder<String> b = ServerSentEvent.builder(data.toString());
        if (event != null && !event.isEmpty()) {
            b.event(event);
        }
        return Flux.just(b.build());
    }

    static String extractDataPayload(String frame) {
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                String payload = line.substring(5);
                data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
            }
        }
        return data.length() == 0 ? null : data.toString();
    }

    static boolean isDoneFrame(String frame) {
        for (String line : frame.split("\n")) {
            String t = line.trim();
            if (t.startsWith("data:") && t.substring(5).trim().equals("[DONE]")) {
                return true;
            }
        }
        return false;
    }

    static Flux<ServerSentEvent<String>> toServerSentEvent(String frame) {
        StringBuilder data = new StringBuilder();
        for (String line : frame.split("\n")) {
            if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                String payload = line.substring(5);
                data.append(payload.startsWith(" ") ? payload.substring(1) : payload);
            }
        }
        if (data.length() == 0) {
            return Flux.empty();
        }
        return Flux.just(ServerSentEvent.builder(data.toString()).build());
    }

    Flux<String> parseSseFrames(Flux<String> raw) {
        StringBuilder buf = new StringBuilder();
        return raw.concatMap(chunk -> {
                    buf.append(chunk);
                    Flux<String> frames = Flux.empty();
                    int idx;
                    while ((idx = indexOfFrameDelimiter(buf)) >= 0) {
                        String frame = buf.substring(0, idx + 2);
                        buf.delete(0, idx + 2);
                        if (!frame.isBlank()) {
                            frames = frames.concatWith(Flux.just(frame));
                        }
                    }
                    return frames;
                })
                .concatWith(Flux.defer(() -> {
                    String tail = buf.toString();
                    buf.setLength(0);
                    return tail.isBlank() ? Flux.empty() : Flux.just(tail);
                }));
    }

    private static String drainToString(DataBuffer buf) {
        try {
            byte[] bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } finally {
            DataBufferUtils.release(buf);
        }
    }

    private static int indexOfFrameDelimiter(StringBuilder buf) {
        int lf = buf.indexOf("\n\n");
        int crlf = buf.indexOf("\r\n\r\n");
        if (lf < 0) {
            return crlf < 0 ? -1 : crlf + 2;
        }
        if (crlf < 0) {
            return lf;
        }
        return Math.min(lf, crlf + 2);
    }

    Flux<ServerSentEvent<String>> withHeartbeat(Flux<ServerSentEvent<String>> upstream) {
        reactor.core.publisher.Sinks.Empty<Void> stop = reactor.core.publisher.Sinks.empty();
        Flux<ServerSentEvent<String>> guarded = upstream
                .doFinally(signal -> stop.tryEmitEmpty());
        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(heartbeatInterval)
                .takeUntilOther(stop.asMono())
                .map(i -> ServerSentEvent.<String>builder().comment("ping").build());
        return Flux.merge(guarded, heartbeat);
    }
}
