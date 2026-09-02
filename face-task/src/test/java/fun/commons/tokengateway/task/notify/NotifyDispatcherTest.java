package fun.commons.tokengateway.task.notify;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NotifyDispatcher 测试 (MockWebServer): 签名头注入 / 无钥不签 / 空地址短路 / 失败退避后成功.
 */
class NotifyDispatcherTest {

    private MockWebServer server;
    private TokenGatewayProperties props;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        props = new TokenGatewayProperties();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private NotifyDispatcher dispatcher() {
        return new NotifyDispatcher(WebClient.builder(), props);
    }

    @Test
    void dispatchSignsBodyWithNotifyKey() throws Exception {
        props.getTask().setNotifySignKey("notify-secret");
        server.enqueue(new MockResponse().setResponseCode(200));
        dispatcher().dispatch("T-1", server.url("/callback").toString(),
                Map.of("task_no", "T-1", "status", "SUCCEEDED"));
        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("T-1").contains("SUCCEEDED");
        assertThat(req.getHeader("X-THMP-Signature"))
                .isEqualTo(ThmpSignature.sign("notify-secret", body));
    }

    @Test
    void dispatchWithoutKeySendsNoSignature() throws Exception {
        props.getTask().setNotifySignKey("");
        server.enqueue(new MockResponse().setResponseCode(200));
        dispatcher().dispatch("T-2", server.url("/cb").toString(), Map.of("task_no", "T-2"));
        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("X-THMP-Signature")).isNull();
    }

    @Test
    void blankUrlShortCircuits() {
        dispatcher().dispatch("T-3", "", Map.of());
        dispatcher().dispatch("T-3", null, Map.of());
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void failureRetriesThenGivesUpQuietly() throws Exception {
        props.getTask().setNotifyRetry(java.util.List.of(
                java.time.Duration.ofMillis(50), java.time.Duration.ofMillis(50)));
        // 首发失败 + 重试 1 失败 + 重试 2 成功 (退避穷尽前)
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200));
        dispatcher().dispatch("T-4", server.url("/cb").toString(), Map.of("task_no", "T-4"));
        // 三次到达各等一段 (退避 50ms 在订阅链上异步进行)
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void exhaustedRetriesDoNotThrow() throws Exception {
        props.getTask().setNotifyRetry(java.util.List.of(java.time.Duration.ofMillis(30)));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        dispatcher().dispatch("T-5", server.url("/cb").toString(), Map.of("task_no", "T-5"));
        // 首发 + 1 次重试后放弃; 用takeRequest 等待两次到达再收尾
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
        Thread.sleep(100); // 穷尽分支在订阅链上安静收尾
        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
