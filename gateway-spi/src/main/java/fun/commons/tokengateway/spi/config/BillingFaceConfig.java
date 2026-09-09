package fun.commons.tokengateway.spi.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 计费面配置 (token-gateway.billing, 设计方案 §5.1/§5.3).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BillingFaceConfig extends EndpointConfig {

    /** 计费模式三值 (非布尔): direct=网关 saga / passthrough=后端自计费 / off=不计费. */
    private BillingMode mode = BillingMode.DIRECT;

    /**
     * 计费三端点路径前缀 (issue #14): 网关拼 pre-consume/settle/refund 后缀.
     * <p>默认 chat 契约 /api/v1/internal/billing, 存量部署零感知; 任务面接入方
     * (有计费变体端点, 如 MMagiX /v1/internal/billing/task/*) 指向前缀切换.
     */
    private String pathPrefix = "/api/v1/internal/billing";

    {
        setTimeout(java.time.Duration.ofSeconds(5));
    }
}
