package fun.commons.tokengateway.relay;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 请求内渠道轮换配置 (对齐 MMagiX gateway-webflux FailoverProperties, 移植 cffc1ea).
 *
 * <pre>
 * gateway.failover:
 *   enabled: true         # false = 关闭轮换 (单渠道快速失败), 失败上报不受影响
 *   max-attempts: 3       # 最大尝试次数 (含首次)
 *   base-backoff-ms: 1000 # 退避基数, 第 n 次失败退避 base * 2^(n-1) ±20% 抖动
 * </pre>
 *
 * @author system
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.failover")
public class FailoverProperties {

    /** 是否启用请求内轮换 */
    private boolean enabled = true;

    /** 最大尝试次数 (含首次) */
    private int maxAttempts = 3;

    /** 退避基数 (毫秒) */
    private long baseBackoffMs = 1000L;

    /**
     * 第 attempt 次 (1-based) 失败后是否还应重试.
     */
    public boolean shouldRetry(int attempt) {
        return enabled && attempt < maxAttempts;
    }

    /**
     * 第 attempt 次 (1-based) 失败后的退避时间: base * 2^(attempt-1).
     */
    public long backoffMs(int attempt) {
        if (attempt < 1) {
            return 0;
        }
        return baseBackoffMs * (1L << (attempt - 1));
    }

    /**
     * 添加抖动 (避免 thundering herd), ±20%.
     */
    public long withJitter(long backoffMs) {
        if (backoffMs <= 0) {
            return 0;
        }
        double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        return (long) (backoffMs * jitter);
    }
}
