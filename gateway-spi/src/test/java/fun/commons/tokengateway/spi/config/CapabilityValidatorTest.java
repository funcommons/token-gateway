package fun.commons.tokengateway.spi.config;

import fun.commons.tokengateway.spi.Capability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CapabilityValidator 单测 (启动期 开关∩能力 校验, 设计方案 §4.1).
 */
@DisplayName("CapabilityValidator")
class CapabilityValidatorTest {

    private static final Set<Capability> FULL = Set.of(Capability.values());

    @Test
    @DisplayName("默认配置 (moderation off + billing direct + access-log on) → 需要四面")
    void defaultRequired() {
        Set<Capability> required = CapabilityValidator.requiredCapabilities(new TokenGatewayProperties());
        assertThat(required).containsExactlyInAnyOrder(
                Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE,
                Capability.BILLING, Capability.ACCESS_LOG);
    }

    @Test
    @DisplayName("开启的面均有能力 → 通过且无 warning")
    void allCapabilitiesPass() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.setFace(Face.LLM);   // 隔离任务面校验 (face=all 缺 lotask.url 会告警)
        props.getModeration().setEnabled(true);
        props.getModelCatalog().setUrl("http://localhost:9400");
        List<String> warnings = CapabilityValidator.validate(props, FULL);
        assertThat(warnings).isEmpty();
    }

    @Test
    @DisplayName("开启 moderation 但适配器未声明 → fail-fast 报缺失能力")
    void missingCapabilityFailsFast() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getModeration().setEnabled(true);
        Set<Capability> partial = Set.of(Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE,
                Capability.BILLING, Capability.ACCESS_LOG);
        assertThatThrownBy(() -> CapabilityValidator.validate(props, partial))
                .isInstanceOf(CapabilityValidator.CapabilityMissingException.class)
                .hasMessageContaining("MODERATION_SCAN");
    }

    @Test
    @DisplayName("billing=passthrough → 不需要 BILLING 面 (上游自计费)")
    void passthroughDoesNotRequireBilling() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getBilling().setMode(BillingMode.PASSTHROUGH);
        assertThat(CapabilityValidator.requiredCapabilities(props))
                .doesNotContain(Capability.BILLING);
        CapabilityValidator.validate(props,
                Set.of(Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE, Capability.ACCESS_LOG));
    }

    @Test
    @DisplayName("billing=off + access-log=off → 裸透传 warning (不阻断)")
    void barePassthroughWarns() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getBilling().setMode(BillingMode.OFF);
        props.getAccessLog().setEnabled(false);
        List<String> warnings = CapabilityValidator.validate(props,
                Set.of(Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE));
        assertThat(warnings).anyMatch(w -> w.contains("裸透传"));
    }

    @Test
    @DisplayName("face=task 但 resource-sign-key 缺失 → warning")
    void taskFaceWithoutSignKeyWarns() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.setFace(Face.TASK);
        props.getTask().getLotask().setUrl("http://localhost:8080"); // 避开 lotask.url fail-fast
        List<String> warnings = CapabilityValidator.validate(props, FULL);
        assertThat(warnings).anyMatch(w -> w.contains("resource-sign-key"));
    }

    @Test
    @DisplayName("face=task 但 lotask.url 缺失 → fail-fast (任务面唯一状态存储)")
    void taskFaceWithoutLotaskUrlFailsFast() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.setFace(Face.TASK);
        assertThatThrownBy(() -> CapabilityValidator.validate(props, FULL))
                .isInstanceOf(CapabilityValidator.CapabilityMissingException.class)
                .hasMessageContaining("task.lotask.url");
    }

    @Test
    @DisplayName("face=all 但 lotask.url 缺失 → warning 降级 (LLM 面仍可服务)")
    void allFaceWithoutLotaskUrlWarns() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        List<String> warnings = CapabilityValidator.validate(props, FULL);
        assertThat(warnings).anyMatch(w -> w.contains("task.lotask.url"));
    }

    @Test
    @DisplayName("face=task + tenant-secret 缺失 → warning (webhook 走回查兜底, 不阻断)")
    void taskFaceWithoutTenantSecretWarns() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.setFace(Face.TASK);
        props.getTask().getLotask().setUrl("http://localhost:8080");
        props.getTask().setResourceSignKey("test-sign-key");
        List<String> warnings = CapabilityValidator.validate(props, FULL);
        assertThat(warnings).anyMatch(w -> w.contains("tenant-secret"));
    }

    @Test
    @DisplayName("auth=none + 非 localhost url → 启动告警 (安全契约 §3.3); localhost 不告警")
    void noneAuthNonLocalhostWarns() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getBilling().setMode(BillingMode.OFF);
        props.getAccessLog().setEnabled(false);
        props.getAccessLog().setTransport(LogTransport.MQ);
        props.getRoute().setUrl("http://route-svc:9400");   // auth 默认 NONE → 应告警
        props.getTokenValidate().setUrl("http://localhost:9400"); // localhost → 不告警
        List<String> warnings = CapabilityValidator.validate(props,
                Set.of(Capability.TOKEN_VALIDATE, Capability.ROUTE_RESOLVE));
        assertThat(warnings).anyMatch(w -> w.contains("route 面 auth=none"));
        assertThat(warnings).noneMatch(w -> w.contains("token-validate 面 auth=none"));
    }

    @Test
    @DisplayName("配置模型默认值对齐设计方案 §5.1 (face/adapter/超时/task 档位)")
    void configDefaults() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        assertThat(props.getFace()).isEqualTo(Face.ALL);
        assertThat(props.getAdapter()).isEqualTo("mmagix");
        assertThat(props.getRoute().getTimeout()).hasSeconds(3);
        assertThat(props.getBilling().getTimeout()).hasSeconds(5);
        assertThat(props.getModeration().getTimeout()).hasSeconds(2);
        assertThat(props.getTask().getExpireScan()).hasHours(24);
        assertThat(props.getTask().getNotifyRetry()).hasSize(3);
        assertThat(props.getAccessLog().getTransport()).isEqualTo(LogTransport.RPC);
    }
}
