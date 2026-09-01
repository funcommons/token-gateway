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
        if (props.getFace() != Face.LLM) {
            validateTaskFace(props, warnings);
        }
        // 安全契约 §3.3: auth=none 仅限 localhost/sidecar 同机隔离
        checkNoneAuth(warnings, "route", props.getRoute());
        checkNoneAuth(warnings, "token-validate", props.getTokenValidate());
        checkNoneAuth(warnings, "billing", props.getBilling());
        checkNoneAuth(warnings, "moderation", props.getModeration());
        if (props.getAccessLog().getTransport() == LogTransport.RPC) {
            checkNoneAuth(warnings, "access-log", props.getAccessLog());
        }
        checkNoneAuth(warnings, "audit", props.getAudit());
        checkNoneAuth(warnings, "model-catalog", props.getModelCatalog());
        return List.copyOf(warnings);
    }

    /**
     * 任务面配置校验 (《06_任务面face-task开发手册》§4).
     *
     * <p>face=task 时 lotask.url 缺失 → fail-fast (任务面唯一状态存储, 缺失即不可用);
     * face=all 时降级 warning (LLM 面仍可服务). tenant-secret 缺失不阻断:
     * webhook 走 verify-then-act 回查兜底 (《05》§8).
     */
    private static void validateTaskFace(TokenGatewayProperties props, List<String> warnings) {
        TaskFaceConfig task = props.getTask();
        LotaskFaceConfig lotask = task.getLotask();
        boolean urlMissing = lotask == null || lotask.getUrl() == null || lotask.getUrl().isBlank();
        if (urlMissing) {
            if (props.getFace() == Face.TASK) {
                throw new CapabilityMissingException(
                        "face=task 但 task.lotask.url 未配置: lotask4j 是任务面唯一状态托管方"
                                + " (《05》零改造接入, 平台前提 V4+)");
            }
            warnings.add("face=all 但 task.lotask.url 未配置, 任务面端点不可用 (LLM 面不受影响)");
        }
        if (task.getResourceSignKey() == null) {
            warnings.add("task.resource-sign-key 未配置, 资源代理签名不可用 (M2.5c 资源代理需要)");
        }
        if (lotask != null && lotask.getTenantSecret() == null) {
            warnings.add("task.lotask.tenant-secret 未配置, webhook 无法验签,"
                    + " 终态事件全量走 verify-then-act 回查兜底 (《05》§8)");
        }
        if (lotask != null && lotask.getAuth() == AuthType.NONE && lotask.getUrl() != null
                && !isLocalhost(lotask.getUrl())) {
            warnings.add("task.lotask auth=none 但 url 非 localhost (" + lotask.getUrl()
                    + "): none 仅限同机隔离, 跨主机请改用 jwt/key (安全契约 §3)");
        }
    }

    /** auth=none + 非 localhost url → 启动告警 (安全契约 §3.3: 白名单不构成认证). */
    private static void checkNoneAuth(List<String> warnings, String face, EndpointConfig cfg) {
        if (cfg.getAuth() == AuthType.NONE && cfg.getUrl() != null && !isLocalhost(cfg.getUrl())) {
            warnings.add(face + " 面 auth=none 但 url 非 localhost (" + cfg.getUrl()
                    + "): none 仅限同机隔离, 跨主机请改用 jwt/key (安全契约 §3)");
        }
    }

    private static boolean isLocalhost(String url) {
        return url.contains("localhost") || url.contains("127.0.0.1") || url.contains("[::1]");
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
