package fun.commons.tokengateway.spi.config;

import fun.commons.tokengateway.spi.Capability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动期 开关∩能力 校验 (设计方案 §3/§4.1, M0 冻结契约).
 *
 * <p>开启的开关没有对应能力实现 → 启动期 fail-fast 报配置错误, 运行期不踩空.
 * 开关组合风险 (设计方案 §11 风险#7, 如裸透传缺审计) → warning 清单, 不阻断启动.
 */
public final class CapabilityValidator {

    private CapabilityValidator() {
    }

    /**
     * 校验配置所需能力 ⊆ 适配器声明能力.
     *
     * @param props        能力面配置 (开关来源)
     * @param capabilities 适配器自述能力 (BackendAdapter.capabilities())
     * @return warning 清单 (组合风险, 不阻断)
     * @throws CapabilityMissingException 有开启的面无对应能力实现 (fail-fast)
     */
    public static List<String> validate(TokenGatewayProperties props, Set<Capability> capabilities) {
        Set<Capability> required = requiredCapabilities(props);
        Set<Capability> missing = new LinkedHashSet<>(required);
        missing.removeAll(capabilities);
        if (!missing.isEmpty()) {
            throw new CapabilityMissingException(
                    "能力面配置开启了适配器 " + props.getAdapter() + " 未声明的能力: " + missing
                            + " (关闭对应开关或补实现, 见后端接入开发手册 §2)");
        }

        List<String> warnings = new ArrayList<>();
        if (props.getBilling().getMode() == BillingMode.OFF && !props.getAccessLog().isEnabled()) {
            warnings.add("billing=off + access-log=off 为裸透传形态 (无计费无日志审计), 仅允许内网路由"
                    + " (设计方案 §11 风险#7)");
        }
        if (!props.isHealthReport()) {
            warnings.add("health-report=off: 渠道健康信号缺失, 需在监控侧补偿 (设计方案 §5.3)");
        }
        if (props.getFace() == Face.TASK && props.getTask().getResourceSignKey() == null) {
            warnings.add("face=task 但 task.resource-sign-key 未配置, 资源代理签名不可用");
        }
        return List.copyOf(warnings);
    }

    /** 由配置开关推导所需能力集. */
    public static Set<Capability> requiredCapabilities(TokenGatewayProperties props) {
        Set<Capability> required = new LinkedHashSet<>();
        // 管线固定两步 (任何形态都需要)
        required.add(Capability.TOKEN_VALIDATE);
        required.add(Capability.ROUTE_RESOLVE);
        if (props.getModelCatalog().getUrl() != null && !props.getModelCatalog().getUrl().isBlank()) {
            required.add(Capability.MODEL_CATALOG);
        }
        if (props.getModeration().isEnabled()) {
            required.add(Capability.MODERATION_SCAN);
        }
        if (props.getBilling().getMode() == BillingMode.DIRECT) {
            required.add(Capability.BILLING);
        }
        if (props.getAccessLog().isEnabled()) {
            required.add(Capability.ACCESS_LOG);
        }
        if (props.getAudit().getUrl() != null && !props.getAudit().getUrl().isBlank()) {
            required.add(Capability.AUDIT);
        }
        // 任务面: face=task/all 默认走网关本地状态机 (复用 route/billing 面, 无新增必需能力);
        // 委托形态 (TASK_CREATE/TASK_POLL) 由部署方显式选择, 不在默认推导内
        return Set.copyOf(required);
    }

    /** 能力缺失 (启动期 fail-fast). */
    public static class CapabilityMissingException extends IllegalStateException {
        public CapabilityMissingException(String message) {
            super(message);
        }
    }
}
