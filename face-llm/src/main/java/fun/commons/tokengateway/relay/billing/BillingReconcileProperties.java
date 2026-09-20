package fun.commons.tokengateway.relay.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 面 settle/refund 失败兜底扫描配置 (issue #23, 对齐任务面 ReconcileJob 风格).
 *
 * <pre>
 * gateway.billing-reconcile:
 *   enabled: true       # false = 不装 BillingReconcileJob (入队不受影响, 死信仍记)
 *   interval: 30s       # 扫描节拍 (@Scheduled fixedDelay/initialDelay)
 *   max-attempts: 5     # 单条 pending 最大重放次数 (耗尽 → 死信; 对齐 mmagix SettleRetryQueue)
 *   base-backoff: 30s   # 退避基数: 第 n 次重放失败后延迟 base * 2^(n-1)
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.billing-reconcile")
public class BillingReconcileProperties {

    /** 是否启用待重放扫描 job (enqueue 侧不受此开关影响). */
    private boolean enabled = true;

    /** 扫描节拍 (@Scheduled fixedDelayString 引用同名配置键). */
    private Duration interval = Duration.ofSeconds(30);

    /** 单条 pending 最大重放次数 (首次入队后的重放上限, 耗尽 → 死信结构化日志). */
    private int maxAttempts = 5;

    /** 退避基数 (毫秒粒度): 第 n 次重放失败后延迟 base * 2^(n-1). */
    private Duration baseBackoff = Duration.ofSeconds(30);

    /**
     * 第 nextRetries 次 (1-based) 重放失败后的下次重试延迟: base * 2^(nextRetries-1).
     */
    public long backoffMs(int nextRetries) {
        if (nextRetries < 1) {
            return 0;
        }
        return baseBackoff.toMillis() * (1L << (nextRetries - 1));
    }
}
