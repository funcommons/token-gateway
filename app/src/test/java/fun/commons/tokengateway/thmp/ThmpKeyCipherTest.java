package fun.commons.tokengateway.thmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ThmpKeyCipher 测试 (fwk4j-sensitive v1.5.1 AesGcmCryptoUtil 移植件回归).
 *
 * <p>与 THMP 侧互操作性由公式钉死: deriveKey = SHA-256(passphrase), GCM(128)/IV12/base64 —
 * THMP 侧 MultiCipherVerificationTest 同源; roundtrip 自证 + 篡改/短文/短口令防线.
 */
@DisplayName("ThmpKeyCipher (AesGcmCryptoUtil 移植)")
class ThmpKeyCipherTest {

    private static final String PASS = "thmp-dev-enc-passphrase-0000000000000001";

    @Test
    @DisplayName("roundtrip: 同口令加解密一致 (与 THMP ThmpTenantCipher 同公式)")
    void roundtrip() {
        ThmpKeyCipher cipher = new ThmpKeyCipher(Map.of(), PASS);
        String cipherText = cipher.encrypt(0, "sk-upstream-secret-key-123");
        assertThat(cipherText).isNotEqualTo("sk-upstream-secret-key-123");
        assertThat(cipher.decrypt(0, cipherText)).isEqualTo("sk-upstream-secret-key-123");
    }

    @Test
    @DisplayName("跨口令不可解 (GCM 标签校验失败) — 对齐 THMP MultiCipherVerificationTest")
    void crossPassphraseUndecryptable() {
        ThmpKeyCipher a = new ThmpKeyCipher(Map.of(), "passphrase-a-000000000000000000000001");
        ThmpKeyCipher b = new ThmpKeyCipher(Map.of(), "passphrase-b-000000000000000000000002");
        String ct = a.encrypt(0, "secret");

        assertThatThrownBy(() -> b.decrypt(0, ct)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("逐租户口令优先于兜底口令")
    void perTenantPassphraseWins() {
        ThmpKeyCipher enc = new ThmpKeyCipher(Map.of(9L, "tenant-9-passphrase-00000000000000001"), PASS);
        ThmpKeyCipher dec = new ThmpKeyCipher(Map.of(9L, "tenant-9-passphrase-00000000000000001"), PASS);
        String ct = enc.encrypt(9, "byok-key");

        assertThat(dec.decrypt(9, ct)).isEqualTo("byok-key");
        // 兜底口令解不了租户 9 的密文
        ThmpKeyCipher fallbackOnly = new ThmpKeyCipher(Map.of(), PASS);
        assertThatThrownBy(() -> fallbackOnly.decrypt(9, ct)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("密文过短 → IllegalArgumentException (ciphertext too short)")
    void tooShortRejected() {
        ThmpKeyCipher cipher = new ThmpKeyCipher(Map.of(), PASS);
        String shortB64 = java.util.Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> cipher.decrypt(0, shortB64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ciphertext too short");
    }

    @Test
    @DisplayName("口令 <32 字符 → IllegalArgumentException (fwk4j deriveKey 硬要求)")
    void shortPassphraseRejected() {
        assertThatThrownBy(() -> new ThmpKeyCipher(Map.of(), "short-pass"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("密文篡改 → 解密失败 (GCM 完整性)")
    void tamperDetected() {
        ThmpKeyCipher cipher = new ThmpKeyCipher(Map.of(), PASS);
        String ct = cipher.encrypt(0, "secret");
        byte[] raw = java.util.Base64.getDecoder().decode(ct);
        raw[raw.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(0, tampered)).isInstanceOf(RuntimeException.class);
    }
}
