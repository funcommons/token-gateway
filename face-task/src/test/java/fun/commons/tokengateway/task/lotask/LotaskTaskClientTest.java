package fun.commons.tokengateway.task.lotask;

import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LotaskTaskClient 契约测试 (MockWebServer 模拟 lotask4j V4+ client 域).
 */
@DisplayName("LotaskTaskClient")
class LotaskTaskClientTest {

    private MockWebServer lotask;
    private LotaskTaskClient client;

    @BeforeEach
    void setUp() throws Exception {
        lotask = new MockWebServer();
        lotask.start();
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().getLotask().setUrl(lotask.url("/").toString().replaceAll("/$", ""));
        props.getTask().getLotask().setAuth(AuthType.JWT);
        props.getTask().getLotask().setJwtSecret("test-jwt-secret");
        props.getTask().getLotask().setAccessKey("ak-test");
        props.getTask().getLotask().setSignKey("sk-test");
        client = new LotaskTaskClient(WebClient.builder(), props, new LotaskAuthSigner(props, WebClient.builder(), new LotaskTokenStore(null)));
    }

    @AfterEach
    void tearDown() throws Exception {
        lotask.shutdown();
    }

    @Test
    @DisplayName("submit 成功: 返回 OpenID; jwt Bearer + HMAC 四头随请求发出")
    void submitSuccess() throws Exception {
        lotask.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"YeirYkxHuQ\"}}"));

        StepVerifier.create(client.submit("video", "T20260901abcdef",
                        Map.of("model", "kling-v1"), "http://gw/internal/lotask/webhook"))
                .expectNext("YeirYkxHuQ")
                .verifyComplete();

        RecordedRequest req = lotask.takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v1/client/tasks/submit");
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getHeader("Authorization")).startsWith("Bearer ");
        assertThat(req.getHeader("X-Access-Key")).isEqualTo("ak-test");
        assertThat(req.getHeader("X-Timestamp")).isNotBlank();
        assertThat(req.getHeader("X-Nonce")).isNotBlank();
        assertThat(req.getHeader("X-Signature")).isNotBlank();
        String sent = req.getBody().readUtf8();
        assertThat(sent).contains("\"idempotencyKey\":\"T20260901abcdef\"");
        assertThat(sent).contains("\"type\":\"video\"");
    }

    @Test
    @DisplayName("submit 业务失败 (code!=0) → 502 + 10004")
    void submitBusinessFail() {
        lotask.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10106,\"message\":\"task_type 未配置\"}"));

        StepVerifier.create(client.submit("video", "T1", Map.of(), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502
                        && re.getCode() == ApiCode.THIRD_PARTY_ERROR.getCode()
                        && re.getMessage().contains("task_type 未配置"))
                .verify();
    }

    @Test
    @DisplayName("get 成功: 映射 status/result/error 字段 (camelCase)")
    void getSuccess() {
        lotask.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"id\":\"YeirYkxHuQ\",\"status\":\"SUCCESS\","
                        + "\"result\":{\"resources\":[\"https://up/v.mp4\"],\"usage\":{\"seconds\":5}},"
                        + "\"errorCode\":null,\"errorMessage\":null}}"));

        StepVerifier.create(client.get("YeirYkxHuQ"))
                .assertNext(view -> {
                    assertThat(view.status()).isEqualTo("SUCCESS");
                    assertThat(view.result().get("resources")).isEqualTo(
                            java.util.List.of("https://up/v.mp4"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("get 任务不存在 (信封 10400) → 404 + 10400")
    void getNotFound() {
        lotask.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"任务不存在\"}"));

        StepVerifier.create(client.get("ghost"))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404
                        && re.getCode() == ApiCode.NOT_FOUND.getCode())
                .verify();
    }

    @Test
    @DisplayName("RPC 异常 (连接拒绝) → 502 + 10004, 不抛基础设施异常")
    void rpcFailure() throws Exception {
        lotask.shutdown();
        StepVerifier.create(client.get("x"))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502
                        && re.getCode() == ApiCode.THIRD_PARTY_ERROR.getCode())
                .verify();
        lotask = new MockWebServer(); // tearDown 幂等
        lotask.start();
    }
}
