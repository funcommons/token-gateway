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

    /**
     * 是否内容审核扫描; off 时管线跳过审核步骤.
     *
     * <p>issue #38 起承诺兑现: {@code enabled=false} (缺省) 时 scan (输入扫描)
     * 与 audit (输出审查) 均在 HttpModerationApi 层短路, 不发任何审核 RPC ——
     * 此前版本本开关未被管线消费, 配了 moderation.url 即逐请求无条件发 RPC
     * (靠 fail-open 兜底), 升级后此类部署审核将停止, 需要审核请显式
     * {@code enabled: true} (启动期 CapabilityValidator 对该误配形态有 WARN).
     */
    private boolean enabled = false;

    /** 审核依赖故障时放行 (fail-open 文档口径三分支); 仅 enabled=true 时有意义. */
    private boolean failOpen = true;

    {
        setTimeout(Duration.ofSeconds(2));
    }
}
