package fun.commons.tokengateway.spi.config;

import lombok.Data;

import java.time.Duration;

/**
 * 能力面端点配置基座 (url + auth 四式 + 超时, 设计方案 §5.1/§5.2).
 *
 * <p>同一服务可复用同一凭证 (如 route/token-validate/model-catalog 同指单体);
 * 不同 host = 服务分离部署, 代码零分支.
 */
@Data
public class EndpointConfig {

    /** 能力面服务地址 (每类独立地址可分离部署, 也可全部指向同一单体). */
    private String url;

    /** 鉴权方式. */
    private AuthType auth = AuthType.NONE;

    /** auth=token 时的静态令牌 (环境变量注入). */
    private String token;

    /** auth=key 时的静态 key (环境变量注入). */
    private String key;

    /** auth=jwt 时的 HS256 共享 secret (环境变量注入; 后端换 secret 须网关同步重铸). */
    private String jwtSecret;

    /** 本面独立超时预算 (SPI 铁律 3: 实现方不得自带超时覆盖). */
    private Duration timeout;
}
