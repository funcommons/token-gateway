package fun.commons.tokengateway.spi.config;

import lombok.Data;

import java.time.Duration;

/**
 * lotask4j 平台对接配置 (token-gateway.task.lotask, face=task/all 时生效).
 *
 * <p>平台前提 V4+ (租户隔离 RLS + 三域鉴权 + webhook HMAC 内置, 《05》§10 零改造口径).
 * 凭证一律环境变量注入, 禁入仓 (安全纪律).
 */
@Data
public class LotaskFaceConfig {

    /** lotask4j 平台地址 (如 http://lotask4j:8080). */
    private String url;

    /** 鉴权方式 (jwt 推荐 / key 内网限定 / none 仅 localhost, 安全契约 §3). */
    private AuthType auth = AuthType.JWT;

    /** auth=jwt 时的 HS256 共享 secret (环境变量注入). */
    private String jwtSecret;

    /** auth=jwt 时的登录主体 = 平台租户名 (client_credentials 的 client_id, 环境变量注入). */
    private String tenantName;

    /** auth=key 时的静态 key (环境变量注入). */
    private String key;

    /** 写操作 HMAC 四头签名: X-Access-Key = 平台租户 name (asts_tenant.name 查钥, 环境变量注入). */
    private String accessKey;

    /** 写操作 HMAC 四头签名: 应用 secret (环境变量注入, 恒定时间比较由平台侧保证). */
    private String signKey;

    /** webhook 验签密钥 = 网关租户 tenant_secret (环境变量注入; 轮换期双钥见《05》§8). */
    private String tenantSecret;

    /** webhook 验签旧密钥 (reset-secret 轮换 grace 期双钥验签, 环境变量注入, 可空). */
    private String tenantSecretPrevious;

    /** 网关 webhook 接收地址 (submit.callbackUrl 下发, 如 https://gateway/internal/lotask/webhook). */
    private String webhookCallbackUrl;

    /**
     * worker 零配置自动注册 (2026-10): 发现新任务类型即 POST /api/v1/admin/types upsert.
     * 缺省开启, 但凭据未配齐时 registrar no-op (缺省静默, 老部署零影响).
     */
    private boolean autoRegister = true;

    /**
     * 平台第二身份 client_id (admin API bearer 用; framework4j 单租户单会话互踢模型下,
     * admin 面须独立于租户身份的专用账号, 否则轮询登录互踢 10205).
     */
    private String platformClientId;

    /** 平台第二身份 client_secret (环境变量注入禁入仓). */
    private String platformClientSecret;

    /** admin/types upsert 所属租户 ID (数字, 平台侧必填). */
    private Long platformTenantId;

    /** 自动注册类型的默认并发限制 (upsert body). */
    private Integer typeConcurrencyLimit = 8;

    /** 自动注册类型的默认超时秒数 (upsert body). */
    private Integer typeTimeoutSeconds = 3600;

    /** 自动注册类型的默认最大重试次数 (upsert body). */
    private Integer typeMaxRetries = 3;

    /** 连接超时. */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** 读超时 (submit/get/cancel 统一预算). */
    private Duration readTimeout = Duration.ofSeconds(5);
}
