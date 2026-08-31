package fun.commons.tokengateway.spi.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Duration;

/**
 * 内容审核面配置 (token-gateway.moderation, 设计方案 §5.1/§5.3).
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ModerationFaceConfig extends EndpointConfig {

    /** 是否内容审核扫描; off 时管线跳过审核步骤. */
    private boolean enabled = false;

    /** 审核依赖故障时放行 (fail-open 文档口径三分支); 仅 enabled=true 时有意义. */
    private boolean failOpen = true;

    {
        setTimeout(Duration.ofSeconds(2));
    }
}
