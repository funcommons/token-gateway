package fun.commons.tokengateway.task.lotask;

import fun.commons.tokengateway.exception.RelayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RouteSnapshotCipher 单测 (《05》§10 R7: AES-GCM 往返 + fail-closed).
 */
@DisplayName("RouteSnapshotCipher")
class RouteSnapshotCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("加解密往返一致; 密文不含明文片段")
    void roundTrip() {
        RouteSnapshotCipher cipher = new RouteSnapshotCipher(KEY);
        String plaintext = "{\"baseUrl\":\"https://up\",\"apiKey\":\"sk-secret-123\"}";
        String encrypted = cipher.encrypt(plaintext);
        assertThat(encrypted).doesNotContain("sk-secret-123");
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("两次加密密文不同 (随机 IV)")
    void randomIv() {
        RouteSnapshotCipher cipher = new RouteSnapshotCipher(KEY);
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("篡改密文 → 解密失败 (GCM tag 校验)")
    void tamperFails() {
        RouteSnapshotCipher cipher = new RouteSnapshotCipher(KEY);
        String encrypted = cipher.encrypt("payload");
        byte[] tampered = Base64.getDecoder().decode(encrypted);
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(tampered)))
                .isInstanceOf(RelayException.class);
    }

    @Test
    @DisplayName("密钥缺失 → 加密 fail-closed (出站凭证不明文落库)")
    void missingKeyFailsClosed() {
        RouteSnapshotCipher cipher = new RouteSnapshotCipher("");
        assertThat(cipher.ready()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(RelayException.class)
                .hasMessageContaining("snapshot-cipher-key");
    }
}
