package fun.commons.tokengateway.task.notify;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebhookVerifier 单测 (《05》§8: 三头全组合 + 双钥 grace).
 */
@DisplayName("WebhookVerifier")
class WebhookVerifierTest {

    private static final String SECRET = "tenant-secret-1";
    private static final byte[] BODY = "{\"id\":\"abc\",\"status\":\"SUCCESS\"}"
            .getBytes(StandardCharsets.UTF_8);

    private TokenGatewayProperties props;
    private WebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        props = new TokenGatewayProperties();
        props.getTask().getLotask().setTenantSecret(SECRET);
        verifier = new WebhookVerifier(props);
    }

    private static String sig(String secret, String timestamp) {
        return ThmpSignature.sign(secret, timestamp + "\n" + new String(BODY, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("三头齐全 + 签名正确 → VERIFIED")
    void verified() {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        assertThat(verifier.verify(ts, sig(SECRET, ts), BODY))
                .isEqualTo(WebhookVerifier.Verdict.VERIFIED);
    }

    @Test
    @DisplayName("签名错误/篡改 body → UNVERIFIED")
    void badSignature() {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        assertThat(verifier.verify(ts, sig("wrong-secret", ts), BODY))
                .isEqualTo(WebhookVerifier.Verdict.UNVERIFIED);
        assertThat(verifier.verify(ts, sig(SECRET, ts), "{}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(WebhookVerifier.Verdict.UNVERIFIED);
    }

    @Test
    @DisplayName("超出 ±5min 防重放窗 → UNVERIFIED")
    void replayWindow() {
        String old = String.valueOf(Instant.now().minusSeconds(3600).toEpochMilli());
        assertThat(verifier.verify(old, sig(SECRET, old), BODY))
                .isEqualTo(WebhookVerifier.Verdict.UNVERIFIED);
    }

    @Test
    @DisplayName("缺头 → UNVERIFIED (回查兜底, 不拒收)")
    void missingHeaders() {
        assertThat(verifier.verify(null, null, BODY))
                .isEqualTo(WebhookVerifier.Verdict.UNVERIFIED);
    }

    @Test
    @DisplayName("密钥轮换 grace: 旧钥签名仍可验 (双钥)")
    void dualKeyGrace() {
        props.getTask().getLotask().setTenantSecret("new-secret");
        props.getTask().getLotask().setTenantSecretPrevious(SECRET);
        String ts = String.valueOf(Instant.now().toEpochMilli());
        assertThat(verifier.verify(ts, sig(SECRET, ts), BODY))
                .isEqualTo(WebhookVerifier.Verdict.VERIFIED);
        assertThat(verifier.verify(ts, sig("new-secret", ts), BODY))
                .isEqualTo(WebhookVerifier.Verdict.VERIFIED);
    }
}
