package fun.commons.tokengateway.task.notify;

import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * lotask4j webhook 三头验签 (《05》§8; 平台 V4+ 内置: X-ASTS-Event-Id/Timestamp/Signature).
 *
 * <p>Signature = Base64(HmacSHA256(tenant_secret, timestamp + "\n" + rawBody)),
 * 密钥 = 网关租户 tenant_secret (环境变量注入). 校验: 时间窗 ±5min 防重放 +
 * 恒定时间比较验签; reset-secret 轮换 grace 期内新旧双钥任一通过即真.
 */
@Component
public class WebhookVerifier {

    /** 防重放时间窗 (与平台投递约定一致). */
    public static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);

    private final TokenGatewayProperties props;

    public WebhookVerifier(TokenGatewayProperties props) {
        this.props = props;
    }

    /**
     * 验签结果: VERIFIED=验签通过可直信; UNVERIFIED=缺头/验签失败, 须 verify-then-act 回查
     * (平台对无租户归属任务静默降级无签名投递, 不能直接拒收).
     */
    public enum Verdict {
        VERIFIED,
        UNVERIFIED
    }

    /**
     * 三头校验.
     *
     * @param timestamp X-ASTS-Timestamp (epoch millis)
     * @param signature X-ASTS-Signature (Base64)
     * @param rawBody   原始请求体字节 (签名与实际投递同源同字节)
     */
    public Verdict verify(String timestamp, String signature, byte[] rawBody) {
        LotaskFaceConfig cfg = props.getTask().getLotask();
        if (timestamp == null || signature == null || rawBody == null) {
            return Verdict.UNVERIFIED;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return Verdict.UNVERIFIED;
        }
        if (Math.abs(Instant.now().toEpochMilli() - ts) > REPLAY_WINDOW.toMillis()) {
            return Verdict.UNVERIFIED;
        }
        String toSign = timestamp + "\n" + new String(rawBody, StandardCharsets.UTF_8);
        for (String secret : activeSecrets(cfg)) {
            String expected = ThmpSignature.sign(secret, toSign);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8))) {
                return Verdict.VERIFIED;
            }
        }
        return Verdict.UNVERIFIED;
    }

    /** 当前密钥 + grace 期旧密钥 (双钥验签). */
    private static List<String> activeSecrets(LotaskFaceConfig cfg) {
        List<String> secrets = new ArrayList<>(2);
        if (cfg.getTenantSecret() != null && !cfg.getTenantSecret().isBlank()) {
            secrets.add(cfg.getTenantSecret());
        }
        if (cfg.getTenantSecretPrevious() != null && !cfg.getTenantSecretPrevious().isBlank()) {
            secrets.add(cfg.getTenantSecretPrevious());
        }
        return secrets;
    }
}
