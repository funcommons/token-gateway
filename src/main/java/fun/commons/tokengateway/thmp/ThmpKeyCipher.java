package fun.commons.tokengateway.thmp;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THMP 候选密钥解密件 (移植, 严格对齐 framework4j-sensitive v1.5.1 AesGcmCryptoUtil +
 * thmp-app ThmpTenantCipher 组合语义; 字节码反编译钉死).
 *
 * <p>来源: 开发原则.md §2.1 复用链 — fwk4j-sensitive 直依赖同样拖 fwk4j 栈污染 reactive 网关 → 移植.
 * 公式 (v1.5.1 钉死): key = SHA-256(passphrase) (口令 ≥32 字符硬要求);
 * 密文 = base64(IV[12] + GCM 密文+tag), GCM tag 128 位, AES/GCM/NoPadding.
 *
 * <p>逐租户口令: 与 THMP ThmpTenantCipher 同构 (tenantPassphrases 优先, 兜底口令兜公共渠道);
 * S2-W3 起步 = 单兜底口令 (dev 口令与 THMP 一致, compose 环境即通), BYOK 逐租户口令/KMS
 * 分发 = 未决#12 决议后在此接口上扩展, 结构不变.
 */
public class ThmpKeyCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final Map<Long, String> tenantPassphrases;
    private final String fallbackPassphrase;
    private final Map<Long, byte[]> keyCache = new ConcurrentHashMap<>();

    public ThmpKeyCipher(Map<Long, String> tenantPassphrases, String fallbackPassphrase) {
        if (fallbackPassphrase == null || fallbackPassphrase.length() < 32) {
            throw new IllegalArgumentException("兜底口令缺失或不足 32 字符 (fwk4j deriveKey 硬要求)");
        }
        this.tenantPassphrases = Map.copyOf(tenantPassphrases);
        this.fallbackPassphrase = fallbackPassphrase;
    }

    /** 加密 (测试 roundtrip 用; 生产加密只在 THMP 侧) */
    public String encrypt(long tenantId, String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            java.security.SecureRandom random = new java.security.SecureRandom();
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyOf(tenantId), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 解密候选 key_cipher_tenant; 跨口令/篡改 → GCM 标签校验失败抛异常 (调用方跳过该候选).
     */
    public String decrypt(long tenantId, String cipherBase64) {
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            if (all.length < IV_LENGTH + 1) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(all, 0, IV_LENGTH);
            byte[] ct = Arrays.copyOfRange(all, IV_LENGTH, all.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyOf(tenantId), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 解密失败: " + e.getMessage(), e);
        }
    }

    private byte[] keyOf(long tenantId) {
        return keyCache.computeIfAbsent(tenantId, id -> {
            String passphrase = tenantPassphrases.getOrDefault(id, fallbackPassphrase);
            return deriveKey(passphrase);
        });
    }

    /** = fwk4j AesGcmCryptoUtil.deriveKey: SHA-256(passphrase UTF-8) */
    private static byte[] deriveKey(String passphrase) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("derive key failed", e);
        }
    }
}
