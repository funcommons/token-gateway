package fun.commons.tokengateway.upstream;

import fun.commons.tokengateway.config.UpstreamPassthroughProperties;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.format.SseTransformer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SsePassthroughInvoker 上游头透传白名单测试 (issue #32).
 *
 * <p>MockWebServer 假上游, 断言白名单命中的客户端头真实到达上游出站请求;
 * 默认空配置 = 出站请求无任何额外头 (零配置回归红线).
 */
@DisplayName("SsePassthroughInvoker 上游头透传白名单")
class SsePassthroughInvokerPassthroughTest {

    private static final String OPENAI_FRAMES = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
            + "data: [DONE]\n\n";

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private DistributeVO channel() {
        return DistributeVO.builder()
                .channelId("1")
                .baseUrl(server.url("/").toString().replaceAll("/$", ""))
                .apiKey("sk-test")
                .protocol("openai")
                .build();
    }

    private SsePassthroughInvoker invoker(UpstreamPassthroughProperties props) {
        // 心跳拉到 10s: 短测试流不会混入 ping comment 事件
        return new SsePassthroughInvoker(WebClient.builder(), Duration.ofSeconds(10), props);
    }

    private UpstreamPassthroughProperties props(String... entries) {
        UpstreamPassthroughProperties p = new UpstreamPassthroughProperties();
        p.getPassthroughHeaders().addAll(List.of(entries));
        return p;
    }

    private HttpHeaders clientHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Mock-Fault", "stream-cut-after=2");
        h.add("X-Mock-Drop", "mid-stream");
        return h;
    }

    private void enqueueOpenAiStream() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(OPENAI_FRAMES));
    }

    @Test
    @DisplayName("配置 X-Mock-*: OpenAI 流式上游收到客户端注入的故障头")
    void openaiStreamReceivesWhitelistedHeader() throws Exception {
        enqueueOpenAiStream();
        SsePassthroughInvoker invoker = invoker(props("X-Mock-*"));

        StepVerifier.create(invoker.invokeStream(channel(), Map.of("model", "m"),
                        (java.util.function.Consumer<String>) null, clientHeaders()))
                .expectNextCount(2)
                .verifyComplete();

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getHeader("X-Mock-Fault")).isEqualTo("stream-cut-after=2");
        assertThat(sent.getHeader("X-Mock-Drop")).isEqualTo("mid-stream");
    }

    @Test
    @DisplayName("默认空配置: 上游收不到任何额外头 (零配置逐字节回归)")
    void defaultConfigForwardsNothing() throws Exception {
        enqueueOpenAiStream();
        SsePassthroughInvoker invoker = invoker(new UpstreamPassthroughProperties());

        StepVerifier.create(invoker.invokeStream(channel(), Map.of("model", "m"),
                        (java.util.function.Consumer<String>) null, clientHeaders()))
                .expectNextCount(2)
                .verifyComplete();

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getHeader("X-Mock-Fault")).isNull();
        assertThat(sent.getHeader("X-Mock-Drop")).isNull();
        // 协议头不受影响
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(sent.getHeader("Accept")).isEqualTo(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    @Test
    @DisplayName("黑名单压过白名单: X-Internal-Token 配置进白名单也不透传")
    void blacklistHeaderNeverForwarded() throws Exception {
        enqueueOpenAiStream();
        SsePassthroughInvoker invoker = invoker(props("X-Internal-Token", "Authorization"));

        HttpHeaders h = new HttpHeaders();
        h.add("X-Internal-Token", "internal-jwt-secret");
        h.add("Authorization", "Bearer client-secret");

        StepVerifier.create(invoker.invokeStream(channel(), Map.of("model", "m"),
                        (java.util.function.Consumer<String>) null, h))
                .expectNextCount(2)
                .verifyComplete();

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getHeader("X-Internal-Token")).isNull();
        // 上游 Authorization 是渠道 apiKey, 不是客户端凭证
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer sk-test");
    }

    @Test
    @DisplayName("Anthropic native 流式: 白名单头透传 + 协议头 x-api-key 不被顶掉")
    void anthropicNativeStreamReceivesWhitelistedHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message_start\ndata: {\"type\":\"message_start\"}\n\n"));
        SsePassthroughInvoker invoker = invoker(props("X-Mock-*"));

        StepVerifier.create(invoker.invokeStreamAnthropicNative(channel(),
                        Map.of("model", "claude-3", "max_tokens", 1),
                        (java.util.function.Consumer<String>) null,
                        clientHeaders()))
                .expectNextMatches(e -> e.data() != null && e.data().contains("message_start"))
                .verifyComplete();

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getHeader("X-Mock-Fault")).isEqualTo("stream-cut-after=2");
        assertThat(sent.getHeader("x-api-key")).isEqualTo("sk-test");
        assertThat(sent.getHeader("anthropic-version")).isEqualTo("2023-06-01");
    }

    @Test
    @DisplayName("Anthropic 转换流式: 白名单头透传到 /v1/messages")
    void anthropicTransformedStreamReceivesWhitelistedHeader() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"ping\"}\n\ndata: [DONE]\n\n"));
        SsePassthroughInvoker invoker = invoker(props("X-Mock-Fault"));
        SseTransformer noop = new SseTransformer() {
            @Override
            public List<ServerSentEvent<String>> transform(Map<String, Object> openAiChunk) {
                return List.of();
            }

            @Override
            public List<ServerSentEvent<String>> onComplete() {
                return List.of();
            }
        };

        StepVerifier.create(invoker.invokeStreamAnthropic(channel(),
                        Map.of("model", "claude-3", "max_tokens", 1), noop, null, clientHeaders()))
                .verifyComplete();

        RecordedRequest sent = server.takeRequest();
        assertThat(sent.getHeader("X-Mock-Fault")).isEqualTo("stream-cut-after=2");
        assertThat(sent.getPath()).isEqualTo("/v1/messages");
    }
}
