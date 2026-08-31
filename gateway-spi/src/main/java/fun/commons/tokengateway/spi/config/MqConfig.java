package fun.commons.tokengateway.spi.config;

import lombok.Data;

/**
 * MQ 通道配置 (access-log.transport=mq 时生效, 设计方案 §5.1).
 */
@Data
public class MqConfig {

    public enum Type {
        KAFKA,
        ROCKETMQ
    }

    private Type type = Type.KAFKA;

    /** kafka=bootstrap-servers; rocketmq=name-server 地址. */
    private String bootstrap;

    /** 默认 token-gateway-access-log; 分区/顺序键 = trace_id. */
    private String topic = "token-gateway-access-log";
}
