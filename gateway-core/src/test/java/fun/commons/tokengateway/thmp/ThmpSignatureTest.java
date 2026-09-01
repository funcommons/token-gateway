package fun.commons.tokengateway.thmp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThmpSignature 公式钉死测试 (移植件回归防线).
 *
 * <p>HMAC 向量取 RFC 4231 Test Case 2 (key="Jefe", data="what do ya want for nothing?"),
 * 独立于被测实现 — 防"实现自己验自己"; 服务端一致性由 thmp-app ContractFaceIT 双向钉死.
 */
@DisplayName("ThmpSignature (fwk4j-signature v1.5.1 移植)")
class ThmpSignatureTest {

    @Test
    @DisplayName("stringToSign: 5 参数 \\n 连接 (v1.5.1 recipe)")
    void buildStringToSignJoinsWithNewline() {
        String sts = ThmpSignature.buildStringToSign("POST", "/v1/candidates/resolve",
                "1725000000000", "nonce-1", "abc123");
        assertThat(sts)
                .isEqualTo("POST\n/v1/candidates/resolve\n1725000000000\nnonce-1\nabc123");
    }

    @Test
    @DisplayName("sign: Base64(HMAC-SHA256) — RFC 4231 TC2 标准向量")
    void signMatchesRfc4231Vector() {
        String sig = ThmpSignature.sign("Jefe", "what do ya want for nothing?");
        assertThat(sig).isEqualTo("W9zBRr9gdU5qBCQmCJV1x1oAPwidJzmDnexYuWTsOEM=");
    }

    @Test
    @DisplayName("sign: 确定性 — 同输入同输出")
    void signIsDeterministic() {
        String sts = ThmpSignature.buildStringToSign("POST", "/p", "1", "n", "m");
        assertThat(ThmpSignature.sign("secret-32-bytes-long-0000000000", sts))
                .isEqualTo(ThmpSignature.sign("secret-32-bytes-long-0000000000", sts));
    }

    @Test
    @DisplayName("md5Hex: 空体 = d41d8cd98f00b204e9800998ecf8427e (RFC 1321)")
    void md5HexEmptyBody() {
        assertThat(ThmpSignature.md5Hex(null))
                .isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
        assertThat(ThmpSignature.md5Hex(new byte[0]))
                .isEqualTo("d41d8cd98f00b204e9800998ecf8427e");
    }

    @Test
    @DisplayName("md5Hex: 小写 hex — \"abc\" 已知向量")
    void md5HexLowercaseKnownVector() {
        assertThat(ThmpSignature.md5Hex("abc".getBytes()))
                .isEqualTo("900150983cd24fb0d6963f7d28e17f72");
    }
}
