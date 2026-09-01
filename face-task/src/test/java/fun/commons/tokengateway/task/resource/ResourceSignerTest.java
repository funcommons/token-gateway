package fun.commons.tokengateway.task.resource;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourceSigner 单测 (exp+sig 能力凭证: 往返/过期/篡改/缺钥).
 */
@DisplayName("ResourceSigner")
class ResourceSignerTest {

    private static ResourceSigner signer(String key) {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().setResourceSignKey(key);
        return new ResourceSigner(props);
    }

    @Test
    @DisplayName("签发-验签往返; sig 为 URL 安全 Base64")
    void roundTrip() {
        ResourceSigner signer = signer("test-resource-key");
        String query = signer.signQuery("T1", 0);
        assertThat(query).startsWith("exp=");
        String sig = query.split("&sig=")[1];
        assertThat(sig).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        long exp = Long.parseLong(query.substring(4, query.indexOf("&")));
        assertThat(signer.verify("T1", 0, exp, sig)).isTrue();
    }

    @Test
    @DisplayName("篡改 (换 taskNo/index/过期) → 验签失败")
    void tamperFails() {
        ResourceSigner signer = signer("test-resource-key");
        String query = signer.signQuery("T1", 0);
        long exp = Long.parseLong(query.substring(4, query.indexOf("&")));
        String sig = query.split("&sig=")[1];
        assertThat(signer.verify("T2", 0, exp, sig)).isFalse();
        assertThat(signer.verify("T1", 1, exp, sig)).isFalse();
        long expired = Instant.now().minusSeconds(3600).getEpochSecond();
        assertThat(signer.verify("T1", 0, expired, sig)).isFalse();
    }

    @Test
    @DisplayName("密钥缺失 → 签发 null + 验签 false (fail-closed)")
    void missingKey() {
        ResourceSigner signer = signer(null);
        assertThat(signer.signQuery("T1", 0)).isNull();
        assertThat(signer.verify("T1", 0, Instant.now().getEpochSecond() + 60, "x")).isFalse();
    }
}
