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

    {
        setTimeout(java.time.Duration.ofSeconds(5));
    }
}
