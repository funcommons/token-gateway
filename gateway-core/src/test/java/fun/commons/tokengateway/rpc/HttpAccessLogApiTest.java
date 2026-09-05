package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.AccessLogRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpAccessLogApi 测试.
 */
@DisplayName("HttpAccessLogApi")
class HttpAccessLogApiTest {

    private MockWebServer backend;
    private HttpAccessLogApi api;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        props.setTimeout(Duration.ofSeconds(2));
        api = new HttpAccessLogApi(WebClient.builder(), new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("record 成功: 200 + Mono.complete")
    void recordSuccess() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.record(AccessLogRequest.builder()
                        .traceId("t1").modelCode("gpt-4o").statusCode(200)
                        .promptTokens(10).completionTokens(5)
                        .creditConsumed(new BigDecimal("0.50"))
                        .latencyMs(120).build()))
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/access-log/record");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"modelCode\":\"gpt-4o\"");
        assertThat(body).contains("\"statusCode\":200");
    }

    @Test
    @DisplayName("RPC 失败 (上游 500) → onErrorResume 空 Mono (不抛)")
    void upstreamError() {
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(api.record(AccessLogRequest.builder().build()))
                .verifyComplete();
    }

    @Test
    @DisplayName("超时 → onErrorResume 空 Mono")
    void timeout() {
        backend.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":null}")
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        StepVerifier.create(api.record(AccessLogRequest.builder().build()))
                .verifyComplete();
    }
}
