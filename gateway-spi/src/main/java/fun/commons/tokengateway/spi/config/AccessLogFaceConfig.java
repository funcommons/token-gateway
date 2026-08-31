package fun.commons.tokengateway.spi.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 访问日志面配置 (token-gateway.access-log, 设计方案 §5.1/§5.3).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AccessLogFaceConfig extends EndpointConfig {

    /** 是否日志落库; off = 仅内存计数 (QPS/错误率自持). */
    private boolean enabled = true;

    /** 投递通道: rpc 同步 / mq 异步 (mq 时存在秒级窗口). */
    private LogTransport transport = LogTransport.RPC;

    /** transport=mq 时生效 (与 url 二选一). */
    private MqConfig mq;
}
