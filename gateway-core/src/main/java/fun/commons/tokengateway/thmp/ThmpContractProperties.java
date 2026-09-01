package fun.commons.tokengateway.thmp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * THMP 契约面客户端配置 (gateway.thmp.*, 22 号 S2-W1 影子双跑).
 *
 * <pre>
 * gateway:
 *   thmp:
 *     enabled: true                    # 影子双跑总开关 (默认关)
 *     base-url: http://localhost:9300  # THMP 契约面地址 (nginx 公网组 9301)
 *     client-id: thmp-mmagix-gw        # 契约租户 client_id (= thmp_tenants.client_id)
 *     secret: ${THMP_CONTRACT_SECRET:} # 契约租户 secret (环境变量注入, 禁入仓)
 *     timeout: 3s                      # 影子调用超时 — 绝不阻塞主链路
 *     cache-ttl: 30s                   # SWR 候选缓存新鲜期
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.thmp")
public class ThmpContractProperties {

    /** 影子双跑总开关 (false 时全链 Noop, 不发任何 THMP 请求) */
    private boolean enabled = false;

    /** THMP 契约面 base url */
    private String baseUrl = "http://localhost:9300";

    /** 契约租户 client_id */
    private String clientId = "";

    /** 契约租户 secret (HMAC 签名密钥, 环境变量注入) */
    private String secret = "";

    /** 单次 resolve 超时 (影子期从严, 不拖累主链) */
    private Duration timeout = Duration.ofSeconds(3);

    /** SWR 候选缓存新鲜期 (过期后台刷新, 先回旧值) */
    private Duration cacheTtl = Duration.ofSeconds(30);

    /** SWR 负缓存窗 (THMP 故障/永错 fail-fast, 防穿透; 短窗保恢复灵敏度) */
    private Duration negativeTtl = Duration.ofSeconds(15);

    /** W3 灰度切流名单 (前台 chat code 列表; 空 = 全部走旧 distribute, 一键回旧 = 清空此单) */
    private java.util.List<String> cutoverModels = java.util.List.of();

    /** W3 灰度切流百分比 0-100 (对切流名单内请求按 model+requestId 确定性分桶) */
    private int cutoverPercent = 0;

    /**
     * 候选密钥解密口令 (与 THMP thmp.enc.passphrase 兜底口令一致, compose/dev 即通;
     * BYOK 逐租户口令/KMS 分发 = 未决#12 决议后在 ThmpKeyCipher 逐租户 map 扩展).
     */
    private String keyPassphrase = "thmp-dev-enc-passphrase-0000000000000001";
}
