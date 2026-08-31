package fun.commons.tokengateway.spi.config;

/**
 * 后端鉴权三式 (设计方案 §5.2; 权威定义 = 《后端服务对接安全契约方案》).
 *
 * <p>TokenHub 契约面 HMAC 四头不走此三式 —— 它是 adapter=tokenhub 的协议形状.
 * 凭证一律环境变量注入禁入仓; 日志/管理面只出现掩码.
 * (token 静态令牌式已于 2026-08-31 决议移除, 既有系统按 jwt 重铸.)
 */
public enum AuthType {
    /** 不发鉴权头 (仅 localhost/sidecar 同机隔离; 非 localhost 启动告警). */
    NONE,
    /** 静态 key 头 X-API-Key (内网限定, 恒定时间比较). */
    KEY,
    /** HS256 签名 JWT (Authorization: Bearer, claims 含 iss/caller/tenant_id; 推荐默认;
     *  跨网段叠加逐请求签名 HMAC 四头). */
    JWT
}
