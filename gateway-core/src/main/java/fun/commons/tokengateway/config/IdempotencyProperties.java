package fun.commons.tokengateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 幂等配置 (gateway.idempotency.*).
 *
 * <pre>
 * gateway:
 *   idempotency:
 *     enabled: true
 *     ttl-hours: 48
 *     key-prefix: "idem:"
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.idempotency")
public class IdempotencyProperties {

    /** 是否启用 Idempotency-Key 去重 (仅客户端带了该头才生效) */
    private boolean enabled = true;

    /** key 存活时长 (小时) */
    private int ttlHours = 48;

    /** Redis key 前缀 */
    private String keyPrefix = "idem:";
}
