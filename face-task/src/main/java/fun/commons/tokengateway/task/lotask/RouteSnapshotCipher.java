package fun.commons.tokengateway.task.lotask;

import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 路由快照字段级加密 (《05》§10 R7 网关侧补偿: AES-GCM, 密钥仅网关与自写 Worker 持有).
 *
 * <p>路由快照含出站凭证 (base_url + apiKey + model_mapping), 随 submit 载荷落 lotask4j 任务表;
 * 平台落库/管理面/日志只见密文. 输出格式 = Base64(iv[12] || ciphertext||tag).
 * 密钥未配置时拒绝加密出站凭证 (fail-closed, 不明文落库).
 */
@Slf4j
@Component
public class RouteSnapshotCipher {

    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;

    public RouteSnapshotCipher(
            @org.springframework.beans.factory.annotation.Value(
                    "${TGW_SNAPSHOT_CIPHER_KEY:${token-gateway.task.snapshot-cipher-key:}}")
            String cipherKey) {
        this.key = cipherKey == null || cipherKey.isBlank()
                ? null : Base64.getDecoder().decode(cipherKey);
    }

    /** 密钥是否已配置. */
    public boolean ready() {
        return key != null;
    }

    /**
     * 加密 (密钥缺失 → 500 fail-closed: 出站凭证不明文落平台库).
     */
    public String encrypt(String plaintext) {
        if (key == null) {
            throw new RelayException(500, ApiCode.SYSTEM_BUSY.getCode(),
                    "task.snapshot-cipher-key 未配置, 路由快照出站凭证拒绝明文落库 (《05》§8)");
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RelayException(500, ApiCode.SYSTEM_BUSY.getCode(), "路由快照加密失败");
        }
    }

    /**
     * 解密 (Worker 侧同密钥; 网关自身对账/调试也可用).
     */
    public String decrypt(String encoded) {
        if (key == null) {
            throw new RelayException(500, ApiCode.SYSTEM_BUSY.getCode(),
                    "task.snapshot-cipher-key 未配置");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RelayException(500, ApiCode.SYSTEM_BUSY.getCode(), "路由快照解密失败");
        }
    }
}
