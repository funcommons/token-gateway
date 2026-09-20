package fun.commons.tokengateway.spi.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 任务计费面配置 (token-gateway.task.billing, issue #31).
 *
 * <p>face=all 单体下 task 面与 LLM 面计费契约互斥 (task 全额 amount 直传 + settle
 * 确认制 vs LLM token 估价 + settle 四维重算退差), 需可独立寻址三端点.
 * <b>逐字段缺省回退</b> {@code token-gateway.billing.*} 对应值 (url/auth/jwt-secret/
 * internal-token/path-prefix/timeout 任一未配即回退) —— path-prefix 回退现通用前缀,
 * 存量把全局前缀指到 task 路径的部署 (issue #12) 行为不变, 零迁移.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TaskBillingFaceConfig extends EndpointConfig {

    /**
     * 任务计费三端点路径前缀 (网关拼 pre-consume/settle/refund 后缀).
     * 缺省 (null/空白) 回退 {@code token-gateway.billing.path-prefix}.
     */
    private String pathPrefix;

    /**
     * 平移态 internal-token 形态凭证 (auth 未显式配时映射 jwt, 同 gateway.backend 平移语义).
     * 缺省回退 billing 面凭证.
     */
    private String internalToken;
}
