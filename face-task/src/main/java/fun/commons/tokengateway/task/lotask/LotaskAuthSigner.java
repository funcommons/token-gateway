package fun.commons.tokengateway.task.lotask;

import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * lotask4j 对接鉴权 (《06》§3.1: jwt 推荐式 + 写操作 HMAC 四头).
 *
 * <p>jwt = HS256 共享 secret 短期令牌 (sub=token-gateway, 1h 有效期, 逐请求现铸避免时钟漂移);
 * 写操作叠加 HMAC 四头 (X-Access-Key/Timestamp/Nonce/Signature), stringToSign 五段式与
 * framework4j-signature v1.5.1 一致 (复用 {@link ThmpSignature}, 密码学原语不重复建设).
 * 凭证均环境变量注入, 不落日志.
 */
@Component
@RequiredArgsConstructor
public class LotaskAuthSigner {

    /** jwt 有效期 (短期现铸; 平台侧应有独立时钟宽容). */
    private static final Duration JWT_TTL = Duration.ofHours(1);

    private final TokenGatewayProperties props;

    private LotaskFaceConfig cfg() {
        return props.getTask().getLotask();
    }

    /**
     * 附加主鉴权头: jwt → Authorization Bearer; key → X-Api-Key; none → 不发 (仅 localhost).
     */
    public void attachAuth(WebClient.RequestHeadersSpec<?> spec) {
        LotaskFaceConfig cfg = cfg();
        switch (cfg.getAuth()) {
            case JWT -> {
                if (cfg.getJwtSecret() != null && !cfg.getJwtSecret().isBlank()) {
                    spec.header("Authorization",
                            "Bearer " + hs256Jwt(cfg.getJwtSecret(), "token-gateway", JWT_TTL));
                }
            }
            case KEY -> {
                if (cfg.getKey() != null && !cfg.getKey().isBlank()) {
                    spec.header("X-Api-Key", cfg.getKey());
                }
            }
            case NONE -> {
                // 仅 localhost/sidecar 同机隔离 (CapabilityValidator 启动告警兜底)
            }
        }
    }

    /**
     * 写操作 (submit/cancel) 附加 HMAC 四头; access-key/sign-key 未配置时不签 (平台侧按应用配置决定是否强制).
     *
     * @param path    不含 query 的请求路径 (如 /api/v1/client/tasks/submit)
     * @param rawBody 请求体原始字节 (签名与实际发送必须同源同字节)
     */
    public void attachSignature(WebClient.RequestHeadersSpec<?> spec, String method, String path,
                                byte[] rawBody) {
        LotaskFaceConfig cfg = cfg();
        if (cfg.getAccessKey() == null || cfg.getAccessKey().isBlank()
                || cfg.getSignKey() == null || cfg.getSignKey().isBlank()) {
            return;
        }
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String toSign = ThmpSignature.buildStringToSign(method, path, timestamp, nonce,
                ThmpSignature.md5Hex(rawBody));
        spec.header("X-Access-Key", cfg.getAccessKey());
        spec.header("X-Timestamp", timestamp);
        spec.header("X-Nonce", nonce);
        spec.header("X-Signature", ThmpSignature.sign(cfg.getSignKey(), toSign));
    }

    /**
     * 最小 HS256 JWT (无 jjwt 依赖; header/payload 固定形状, sub 固定 token-gateway).
     */
    static String hs256Jwt(String secret, String subject, Duration ttl) {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(("{\"sub\":\"" + subject + "\",\"iat\":" + now
                + ",\"exp\":" + (now + ttl.getSeconds()) + "}").getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return content + "." + base64Url(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("lotask jwt 签发失败", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
