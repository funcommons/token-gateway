package fun.commons.tokengateway.task.resource;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 资源代理 sig 能力凭证 (《05》§4: exp+sig 24h, 上游原始 URL 永不透传).
 *
 * <p>sig = Base64Url(HmacSHA256(resourceSignKey, taskNo + ":" + index + ":" + exp)),
 * 校验恒定时间比较 (安全纪律). 密钥缺失时签名/验签均不可用 (CapabilityValidator 已告警).
 */
@Component
public class ResourceSigner {

    /** 代理 URL 有效窗口. */
    public static final Duration SIG_TTL = Duration.ofHours(24);

    private final TokenGatewayProperties props;

    public ResourceSigner(TokenGatewayProperties props) {
        this.props = props;
    }

    private String signKey() {
        return props.getTask().getResourceSignKey();
    }

    /** 生成代理查询串 (exp=epoch 秒, sig=签名); 密钥缺失返回 null. */
    public String signQuery(String taskNo, int index) {
        if (signKey() == null || signKey().isBlank()) {
            return null;
        }
        long exp = Instant.now().plus(SIG_TTL).getEpochSecond();
        return "exp=" + exp + "&sig=" + sign(taskNo, index, exp);
    }

    /**
     * 验签: 过期/密钥缺失/签名不符 → false. 恒定时间比较 (防时序侧信道).
     */
    public boolean verify(String taskNo, int index, long exp, String sig) {
        if (signKey() == null || signKey().isBlank() || sig == null || sig.isBlank()) {
            return false;
        }
        if (Instant.now().getEpochSecond() > exp) {
            return false;
        }
        String expected = sign(taskNo, index, exp);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String taskNo, int index, long exp) {
        String raw = ThmpSignature.sign(signKey(), taskNo + ":" + index + ":" + exp);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Base64.getDecoder().decode(raw));
    }
}
