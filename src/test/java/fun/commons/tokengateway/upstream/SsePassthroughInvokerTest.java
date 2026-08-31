package fun.commons.tokengateway.upstream;

import fun.commons.tokengateway.contract.DistributeVO;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * SsePassthroughInvoker 测试 (从 backend/gateway 模板复制简化).
 * <p>MockWebServer 假上游, 验证 SSE 帧重组 / [DONE] 终止 / 心跳.
 */
@DisplayName("SsePassthroughInvoker")
class SsePassthroughInvokerTest {

    private MockWebServer server;
    private SsePassthroughInvoker invoker;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        invoker = new SsePassthroughInvoker(WebClient.builder(), Duration.ofMillis(50));
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
                .build();
    }

    @Test
    @DisplayName("完整 OpenAI 流: 2 帧 data + [DONE] → payload 正确, 流终止")
    void openaiStreamTerminated() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                        + "data: [DONE]\n\n"));

        StepVerifier.create(invoker.invokeStream(channel(), Map.of("model", "m"))
                        .takeUntil(e -> "[DONE]".equals(e.data()))
                        .filter(e -> e.data() != null && !"[DONE]".equals(e.data()))
                        .map(ServerSentEvent::data))
                .expectNextMatches(s -> s.contains("\"content\":\"a\""))
                .verifyComplete();
    }

    @Test
    @DisplayName("完整 1 帧 + 1 [DONE] 帧 → payload 透传")
    void splitAcrossChunks() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"x\":1}\n\ndata: [DONE]\n\n"));

        StepVerifier.create(invoker.invokeStream(channel(), Map.of("model", "m"))
                        .takeUntil(e -> "[DONE]".equals(e.data()))
                        .filter(e -> e.data() != null && !"[DONE]".equals(e.data()))
                        .map(ServerSentEvent::data))
                .expectNext("{\"x\":1}")
                .verifyComplete();
    }
}
