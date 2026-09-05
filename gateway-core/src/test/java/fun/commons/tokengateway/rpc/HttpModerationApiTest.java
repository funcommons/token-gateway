package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.moderation.ModerationOutcome;
import fun.commons.tokengateway.moderation.ScanRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpModerationApi 测试: MockWebServer 假主应用, 验证 RPC 请求 + 响应解析 + 三态映射 + fail-open.
 */
@DisplayName("HttpModerationApi RPC")
class HttpModerationApiTest {

    private MockWebServer server;
    private HttpModerationApi api;
    private fun.commons.tokengateway.spi.config.TokenGatewayProperties spi;
    private GatewayProperties props;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        props = new GatewayProperties();
        props.setUrl(server.url("/").toString().replaceAll("/$", ""));
        props.setInternalToken("test-internal-token");
        props.setTimeout(Duration.ofSeconds(2));
        spi = new fun.commons.tokengateway.spi.config.TokenGatewayProperties();
        api = new HttpModerationApi(WebClient.builder(),
                new CapabilityEndpoints(spi, props), new RpcInternalAuth(props), spi);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private ScanRequest buildReq() {
        ScanRequest r = new ScanRequest();
        r.setTenantId("100");
        r.setUserId("200");
        r.setContent("帮我做题");
        r.setDirection("INPUT");
        return r;
    }

    @Test
    @DisplayName("BLOCK 命中 → BLOCK_REQUEST + ruleCodes 透传")
    void blockOutcome() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":false,"
                        + "\"actionTaken\":\"BLOCK\","
                        + "\"matches\":[{\"ruleCode\":\"sensitive_word\",\"matchedText\":\"帮我做题\",\"severity\":2}],"
                        + "\"sanitizedContent\":null}}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.BLOCK_REQUEST);
                    assertThat(outcome.isBlocked()).isTrue();
                    assertThat(outcome.ruleCodes()).containsExactly("sensitive_word");
                    assertThat(outcome.sanitizedContent()).isNull();
                })
                .verifyComplete();

        var recorded = server.takeRequest();
        assertThat(recorded.getHeader("X-Internal-Token")).isEqualTo("test-internal-token");
        assertThat(recorded.getPath()).isEqualTo("/v1/internal/moderation/scan");
        assertThat(recorded.getBody().readUtf8()).contains("\"tenantId\":\"100\"")
                .contains("\"content\":\"帮我做题\"")
                .contains("\"direction\":\"INPUT\"");
    }

    @Test
    @DisplayName("MASK 命中 → MASK_CONTENT + sanitizedContent 透传")
    void maskOutcome() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,"
                        + "\"actionTaken\":\"MASK\",\"matches\":[],"
                        + "\"sanitizedContent\":\"<PII>\"}}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.MASK_CONTENT);
                    assertThat(outcome.sanitizedContent()).isEqualTo("<PII>");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("无命中 (LOG) → PASS_THROUGH + sanitizedContent 透传")
    void passOutcome() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,"
                        + "\"actionTaken\":\"LOG\",\"matches\":[],"
                        + "\"sanitizedContent\":\"原文\"}}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.PASS_THROUGH);
                    assertThat(outcome.sanitizedContent()).isEqualTo("原文");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("RPC 失败 (主应用 5xx) → fail-open PASS_THROUGH 降级")
    void upstreamErrorFailsOpen() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("server down"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.PASS_THROUGH);
                    assertThat(outcome.sanitizedContent()).isEqualTo("帮我做题");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("RPC 超时 → fail-open")
    void timeoutFailsOpen() {
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}")
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> assertThat(outcome.action())
                        .isEqualTo(ModerationOutcome.Action.PASS_THROUGH))
                .verifyComplete();
    }

    @Test
    @DisplayName("主应用返 fail 包络 (code != 0) → fail-open PASS_THROUGH")
    void mainAppBusinessFailFailsOpen() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10100,\"message\":\"参数错误\",\"data\":null}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> assertThat(outcome.action())
                        .isEqualTo(ModerationOutcome.Action.PASS_THROUGH))
                .verifyComplete();
    }

    @Test
    @DisplayName("data=null → fail-open (防 NPE)")
    void nullDataFailsOpen() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> assertThat(outcome.action())
                        .isEqualTo(ModerationOutcome.Action.PASS_THROUGH))
                .verifyComplete();
    }

    @Test
    @DisplayName("多 rule 命中 → ruleCodes 全部透传")
    void multipleRuleCodes() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":false,\"actionTaken\":\"BLOCK\","
                        + "\"matches\":["
                        + "{\"ruleCode\":\"injection_pattern\",\"severity\":3},"
                        + "{\"ruleCode\":\"pii_phone\",\"severity\":2},"
                        + "{\"ruleCode\":\"sensitive_word\",\"severity\":2}"
                        + "]}}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.ruleCodes()).containsExactlyInAnyOrder(
                            "injection_pattern", "pii_phone", "sensitive_word");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("BLOCK 但 matches=null → BLOCK_REQUEST + ruleCodes=空")
    void blockWithNullMatches() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":false,\"actionTaken\":\"BLOCK\","
                        + "\"matches\":null,\"sanitizedContent\":null}}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.BLOCK_REQUEST);
                    assertThat(outcome.ruleCodes()).isEqualTo(List.of());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("G6 fail-closed: fail-open=false 时 RPC 故障 → BLOCK (MODERATION_UNAVAILABLE)")
    void failClosedBlocksOnRpcFailure() {
        spi.getModeration().setFailOpen(false);
        server.enqueue(new MockResponse().setResponseCode(500).setBody("server down"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.BLOCK_REQUEST);
                    assertThat(outcome.ruleCodes())
                            .containsExactly(HttpModerationApi.DEGRADE_RULE);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("G6 fail-closed: 业务级 fail 包络 → 同样 BLOCK")
    void failClosedBlocksOnBusinessFail() {
        spi.getModeration().setFailOpen(false);
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10100,\"message\":\"参数错误\",\"data\":null}"));

        StepVerifier.create(api.scan(buildReq()))
                .assertNext(outcome -> assertThat(outcome.isBlocked()).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("G6 face 寻址: token-gateway.moderation.url 优先于 gateway.backend.url")
    void faceUrlWinsOverLegacyFallback() throws Exception {
        // 独立审核服务地址 (fail-closed 部署形态的分离部署)
        try (okhttp3.mockwebserver.MockWebServer dedicated = new okhttp3.mockwebserver.MockWebServer()) {
            dedicated.start();
            spi.getModeration().setUrl(dedicated.url("/").toString().replaceAll("/$", ""));
            spi.getModeration().setFailOpen(false);
            dedicated.enqueue(new MockResponse().setResponseCode(503).setBody("down"));

            StepVerifier.create(api.scan(buildReq()))
                    .assertNext(outcome -> assertThat(outcome.isBlocked()).isTrue())
                    .verifyComplete();
            assertThat(dedicated.getRequestCount()).isEqualTo(1);
            assertThat(server.getRequestCount()).isZero();
        }
    }
}
