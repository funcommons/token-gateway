package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.framework.ApiResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThmpContractClient 测试: MockWebServer 假 THMP 契约面.
 *
 * <p>核心断言: 服务端按收到的 headers + body **独立重算签名** 验证通过 (端到端公式证明,
 * 等价 fwk4j-signature 拦截器行为), 而非仅检查头存在.
 */
@DisplayName("ThmpContractClient (HMAC 契约面)")
class ThmpContractClientTest {

    private static final String SECRET = "thmp-contract-secret-0000000000000001";

    private MockWebServer server;
    private ThmpContractClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        ThmpContractProperties props = new ThmpContractProperties();
        props.setEnabled(true);
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        props.setClientId("thmp-mmagix-gw");
        props.setSecret(SECRET);
        props.setTimeout(Duration.ofSeconds(2));
        client = new ThmpContractClient(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static final String RESOLVE_BODY_JSON =
            "{\"code\":0,\"message\":\"success\",\"data\":{\"candidates\":"
                    + "[{\"procurement_model_id\":\"11\",\"channel_id\":\"5\",\"priority\":1,"
                    + "\"upstream_base_url\":\"https://up.internal/v1\",\"protocol_type\":\"openai\","
                    + "\"key_select_mode\":\"ROUND_ROBIN\",\"keys\":[{\"key_id\":\"7\","
                    + "\"key_cipher_tenant\":\"cipher-x\"}],\"model_params\":{\"max_tokens\":4096},"
                    + "\"capacity\":null,\"cost\":{\"cost_mode\":\"COST_FIRST\",\"currency\":\"CNY\","
                    + "\"cost_items\":[]}}],\"blocked\":[],\"affinity_hit\":false,\"cache_hit\":false},"
                    + "\"error\":null,\"trace_id\":\"t1\",\"timestamp\":1}";

    @Test
    @DisplayName("resolve: 路径/四头齐全 + 服务端独立重算签名通过 + 候选结构解析")
    void resolveSignsAndParses() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(RESOLVE_BODY_JSON));

        StepVerifier.create(client.resolve("gpt-4o", "0"))
                .assertNext(resp -> {
                    assertThat(resp.isSuccess()).isTrue();
                    ThmpContractClient.ResolveResult result = resp.getData();
                    assertThat(result.hasCandidates()).isTrue();
                    ThmpContractClient.Candidate c = result.candidates().get(0);
                    assertThat(c.upstream_base_url()).isEqualTo("https://up.internal/v1");
                    assertThat(c.protocol_type()).isEqualTo("openai");
                    assertThat(c.keys()).hasSize(1);
                    assertThat(c.keys().get(0).key_cipher_tenant()).isEqualTo("cipher-x");
                })
                .verifyComplete();

        var recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/candidates/resolve");
        String accessKey = recorded.getHeader("X-Access-Key");
        String timestamp = recorded.getHeader("X-Timestamp");
        String nonce = recorded.getHeader("X-Nonce");
        String signature = recorded.getHeader("X-Signature");
        assertThat(accessKey).isEqualTo("thmp-mmagix-gw");

        // 服务端独立重算: md5(实际收到的 body) → sts → HMAC → 与 X-Signature 一致
        byte[] bodyBytes = recorded.getBody().readUtf8().getBytes(StandardCharsets.UTF_8);
        String sts = ThmpSignature.buildStringToSign("POST", "/v1/candidates/resolve",
                timestamp, nonce, ThmpSignature.md5Hex(bodyBytes));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal(sts.getBytes(StandardCharsets.UTF_8)));
        assertThat(signature).isEqualTo(expected);
        assertThat(new String(bodyBytes, StandardCharsets.UTF_8))
                .contains("\"model_code\":\"gpt-4o\"")
                .contains("\"tenant_id\":\"0\"");
    }

    @Test
    @DisplayName("resolve: 空 tenant 归 \"0\"")
    void resolveNormalizesBlankTenant() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"success\",\"data\":{\"candidates\":[],"
                        + "\"blocked\":[],\"affinity_hit\":false,\"cache_hit\":false},"
                        + "\"error\":null,\"trace_id\":\"t\",\"timestamp\":1}"));

        StepVerifier.create(client.resolve("claude-x", null))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue())
                .verifyComplete();

        assertThat(server.takeRequest().getBody().readUtf8()).contains("\"tenant_id\":\"0\"");
    }

    @Test
    @DisplayName("resolve: 信封 code 非 0 原样透传 (isSuccess=false)")
    void resolvePassesThroughBusinessError() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"目录模型不存在\",\"data\":null,"
                        + "\"error\":null,\"trace_id\":\"t\",\"timestamp\":1}"));

        StepVerifier.create(client.resolve("no-such-model", "0"))
                .assertNext(resp -> {
                    assertThat(resp.isFail()).isTrue();
                    assertThat(resp.getCode()).isEqualTo(10400);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("resolve: HTTP 500 → Mono.error")
    void resolveHttpError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("down"));
        StepVerifier.create(client.resolve("m", "0"))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("resolve: 超时 → Mono.error (影子期 2s 预算内)")
    void resolveTimeout() {
        server.enqueue(new MockResponse()
                .setBody(RESOLVE_BODY_JSON)
                .setBodyDelay(5, TimeUnit.SECONDS));
        StepVerifier.create(client.resolve("m", "0"))
                .expectError()
                .verify();
    }
}
