package fun.commons.tokengateway.thmp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * THMP 契约面 HMAC 签名 (移植, 严格对齐 framework4j-signature v1.5.1 语义).
 *
 * <p>来源: 开发原则.md §2.1 复用链 — framework4j-signature 直依赖会传递 fwk4j-web/fwk4j-redis
 * + servlet-api (servlet 栈), 污染本 reactive 网关, 故移植三件套语义 (字节码反编译钉死):
 * <ul>
 *   <li>{@link #buildStringToSign} — 5 参数以 \n 相连 (v1.5.1 常量池 recipe 钉死)</li>
 *   <li>{@link #sign} — Base64(HMAC-SHA256(secret, stringToSign)) (MacUtil.hmacSha256Base64)</li>
 *   <li>{@link #md5Hex} — 小写 hex MD5 (BodyMd5Util.md5Hex, 空体 = 空串摘要 d41d8...)</li>
 * </ul>
 *
 * <p>服务端 (thmp-app) 用同一 fwk4j 拦截器验签, 双端公式一致由 ContractFaceIT + 本类单测双向钉死.
 * 升级 framework4j 时须同步核对本类 (fwk4j v1.5.2+ 若改 recipe 必须移植更新).
 */
public final class ThmpSignature {

    private ThmpSignature() {
    }

    /**
     * 构造待签名串: method \n path \n timestamp \n nonce \n bodyMd5.
     *
     * <p>path 为不含 query 的请求路径 (如 /v1/candidates/resolve); bodyMd5 为请求体字节的小写 hex MD5.
     */
    public static String buildStringToSign(String method, String path, String timestamp,
                                           String nonce, String bodyMd5) {
        return method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyMd5;
    }

    /**
     * HMAC-SHA256 签名, Base64 编码 (对齐 fwk4j MacUtil.hmacSha256Base64).
     */
    public static String sign(String secret, String stringToSign) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("THMP HMAC 签名失败", e);
        }
    }

    /**
     * 小写 hex MD5 (对齐 fwk4j BodyMd5Util.md5Hex; null 视为空字节).
     */
    public static String md5Hex(byte[] body) {
        try {
            byte[] bytes = body == null ? new byte[0] : body;
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 摘要失败", e);
        }
    }
}
