package fun.commons.tokengateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 限流配置 (gateway.ratelimit.*).
 *
 * <pre>
 * gateway:
 *   ratelimit:
 *     enabled: true
 *     limit: 100           # 窗口内最大请求数
 *     window-seconds: 60   # 滑动窗口大小
 *     key-prefix: "ratelimit:"
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.ratelimit")
public class RateLimitProperties {

    /** 是否启用限流 */
    private boolean enabled = true;

    /** 窗口内最大请求数 */
    private int limit = 100;

    /** 滑动窗口大小 (秒) */
    private int windowSeconds = 60;

    /** Redis key 前缀 */
    private String keyPrefix = "ratelimit:";
}
