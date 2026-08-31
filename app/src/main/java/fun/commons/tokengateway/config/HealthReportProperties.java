package fun.commons.tokengateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 渠道健康上报开关 (gateway.health-report).
 *
 * <p>对齐设计方案 §5.3: 默认开启 —— 上游调用成功/失败回传 record-success/record-failure,
 * 后端渠道模块据此做熔断与质量选型; off 时渠道健康信号缺失需在监控侧补偿.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.health-report")
public class HealthReportProperties {

    /** 是否回传渠道健康信号 (record-success / record-failure). 默认 true. */
    private boolean enabled = true;
}
