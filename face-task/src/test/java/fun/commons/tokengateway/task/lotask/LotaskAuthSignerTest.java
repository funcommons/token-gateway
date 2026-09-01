package fun.commons.tokengateway.task.lotask;

import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
        new LotaskAuthSigner(props(AuthType.JWT)).attachSignature(spec, "POST", "/x", new byte[0]);
        // 静默跳过即通过
    }
}
