package fun.commons.tokengateway.task.lotask;

import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LotaskAuthSigner 单测 (jwt 三式 + HMAC 四头存在性).
 */
@DisplayName("LotaskAuthSigner")
class LotaskAuthSignerTest {

    private static TokenGatewayProperties props(AuthType auth) {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().getLotask().setAuth(auth);
        props.getTask().getLotask().setJwtSecret("test-jwt-secret");
        props.getTask().getLotask().setKey("test-key");
        return props;
    }

    @Test
    @DisplayName("jwt 式: Authorization Bearer 三段式 HS256, 签名可复算")
    void jwtAuth() {
        String jwt = LotaskAuthSigner.hs256Jwt("test-jwt-secret", "token-gateway",
                java.time.Duration.ofHours(1));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[0]))).contains("HS256");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-jwt-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
            assertThat(parts[2]).isEqualTo(expected);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("缺 access-key/sign-key → 不签名不阻断 (启动期已有 warning; 四头存在性由 LotaskTaskClientTest 经 MockWebServer 断言)")
    void missingSignKeysSkip() {
        WebClient.RequestHeadersSpec<?> spec = WebClient.builder().build().post()
                .uri("http://localhost/x");
        new LotaskAuthSigner(props(AuthType.JWT), WebClient.builder(),
                new LotaskTokenStore(null)).attachSignature(spec, "POST", "/x", new byte[0]);
        // 静默跳过即通过
    }

    /** 构造带未来 exp 的伪 JWT (仅 payload 形状有效, 平台外无校验). */
    private static String fakeToken(long expMs) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"exp\":" + expMs + "}").getBytes(StandardCharsets.UTF_8));
        return "hdr." + payload + ".sig";
    }

    private static TokenGatewayProperties loginProps(String baseUrl) {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().getLotask().setAuth(AuthType.JWT);
        props.getTask().getLotask().setUrl(baseUrl);
        props.getTask().getLotask().setTenantName("token-gateway-it");
        props.getTask().getLotask().setJwtSecret("s");
        return props;
    }

    @Test
    @DisplayName("bearer: 首次登录写共享 store, 第二次复用缓存 (不重复登录) — 单会话互斥的适配基础")
    void bearerSharesViaStore() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            String token = fakeToken(System.currentTimeMillis() + 3600_000);
            server.enqueue(new MockResponse().setBody(
                    "{\"code\":0,\"data\":{\"access_token\":\"" + token + "\"}}"));
            server.enqueue(new MockResponse().setBody(
                    "{\"code\":0,\"data\":{\"access_token\":\"SHOULD-NOT-BE-USED\"}}"));
            LotaskAuthSigner signer = new LotaskAuthSigner(
                    loginProps(server.url("/").toString()), WebClient.builder(),
                    new LotaskTokenStore(null));
            String first = signer.bearer().block(java.time.Duration.ofSeconds(3));
            String second = signer.bearer().block(java.time.Duration.ofSeconds(3));
            org.assertj.core.api.Assertions.assertThat(first).isEqualTo(token);
            org.assertj.core.api.Assertions.assertThat(second).isEqualTo(token);
            org.assertj.core.api.Assertions.assertThat(server.getRequestCount()).isEqualTo(1);
        } finally {
            server.shutdown();
        }
    }

    @Test
    @DisplayName("invalidate: 清共享缓存后 bearer 重新登录 (401 自愈路径)")
    void invalidateForcesRelogin() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            server.enqueue(new MockResponse().setBody(
                    "{\"code\":0,\"data\":{\"access_token\":\"" + fakeToken(1) + "\"}}"));
            server.enqueue(new MockResponse().setBody(
                    "{\"code\":0,\"data\":{\"access_token\":\"" + fakeToken(2) + "\"}}"));
            LotaskAuthSigner signer = new LotaskAuthSigner(
                    loginProps(server.url("/").toString()), WebClient.builder(),
                    new LotaskTokenStore(null));
            String first = signer.bearer().block(java.time.Duration.ofSeconds(3));
            signer.invalidate().block(java.time.Duration.ofSeconds(3));
            StepVerifier.create(signer.bearer())
                    .expectNextMatches(t -> !t.equals(first))
                    .verifyComplete();
            org.assertj.core.api.Assertions.assertThat(server.getRequestCount()).isEqualTo(2);
        } finally {
            server.shutdown();
        }
    }
}
