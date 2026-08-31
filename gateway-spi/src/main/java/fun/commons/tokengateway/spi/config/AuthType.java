package fun.commons.tokengateway.spi.config;

/**
 * 后端鉴权四式 (设计方案 §5.2).
 *
 * <p>TokenHub 契约面 HMAC 四头不走此四式 —— 它是 adapter=tokenhub 的协议形状.
 * 凭证一律环境变量注入禁入仓; 日志/管理面只出现掩码.
 */
public enum AuthType {
    /** 不发鉴权头 (内网信任/白名单). */
    NONE,
    /** 静态 key 头 X-API-Key. */
    KEY,
    /** HS256 签名 JWT (Authorization: Bearer, claims 含 iss/caller/tenant_id; 现网 internal-token 形态). */
    JWT,
    /** 静态不透明令牌头 X-Internal-Token. */
    TOKEN
}
