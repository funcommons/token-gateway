package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CapabilityEndpoints#taskBilling 回退矩阵 (issue #31): 任一字段缺省回退
 * {@code token-gateway.billing.*} 对应值 (billing 自身仍有 backend.* 平移回退);
 * 仅 path-prefix 覆盖时 url/auth/timeout 仍回退通用 —— 存量 #12 部署零迁移红线.
 */
@DisplayName("CapabilityEndpoints#taskBilling (issue #31 逐字段回退)")
class CapabilityEndpointsTaskBillingTest {

    private static final String BACKEND_URL = "http://backend:9400";

    private static GatewayProperties legacy() {
        var props = new GatewayProperties();
        props.setUrl(BACKEND_URL);
        props.setInternalToken("legacy-tok");
        props.setTimeout(Duration.ofSeconds(10));
        return props;
    }

    @Test
    @DisplayName("全缺省: taskBilling 与 billing 全字段一致 (url/auth/凭证/path/timeout, 含 backend.* 平移)")
    void allDefaultsFallBackToBilling() {
        var spi = new TokenGatewayProperties();
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig task = endpoints.taskBilling();
        EndpointConfig generic = endpoints.billing();
        assertThat(task.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(task.getPath()).isEqualTo("/api/v1/internal/billing");
        assertThat(task.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(task.getJwtSecret()).isEqualTo("legacy-tok");
        assertThat(task.getKey()).isEqualTo("legacy-tok");
        assertThat(task.getTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(task.getUrl()).isEqualTo(generic.getUrl());
        assertThat(task.getPath()).isEqualTo(generic.getPath());
        assertThat(task.getAuth()).isEqualTo(generic.getAuth());
        assertThat(task.getJwtSecret()).isEqualTo(generic.getJwtSecret());
        assertThat(task.getTimeout()).isEqualTo(generic.getTimeout());
    }

    @Test
    @DisplayName("仅 task.billing.path-prefix 覆盖: path 取 task 值, url/auth/凭证/timeout 仍回退通用")
    void onlyPathPrefixOverriddenRestFallsBack() {
        var spi = new TokenGatewayProperties();
        spi.getTask().getBilling().setPathPrefix("/v1/internal/billing/task");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig task = endpoints.taskBilling();
        assertThat(task.getPath()).isEqualTo("/v1/internal/billing/task");
        assertThat(task.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(task.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(task.getJwtSecret()).isEqualTo("legacy-tok");
        assertThat(task.getTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("独立 url/auth/jwt-secret/timeout: 全取 task 值, path 仍回退通用前缀")
    void independentUrlAuthSecretTimeout() {
        var spi = new TokenGatewayProperties();
        var tb = spi.getTask().getBilling();
        tb.setUrl("http://task-billing:9401");
        tb.setAuth(AuthType.JWT);
        tb.setJwtSecret("task-s3cret");
        tb.setTimeout(Duration.ofSeconds(3));
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig task = endpoints.taskBilling();
        assertThat(task.getUrl()).isEqualTo("http://task-billing:9401");
        assertThat(task.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(task.getJwtSecret()).isEqualTo("task-s3cret");
        assertThat(task.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(task.getPath()).isEqualTo("/api/v1/internal/billing");
    }

    @Test
    @DisplayName("仅 internal-token (auth 未配): 平移语义映射 jwt, 其余回退通用")
    void internalTokenAloneMapsToJwt() {
        var spi = new TokenGatewayProperties();
        spi.getTask().getBilling().setInternalToken("task-tok");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig task = endpoints.taskBilling();
        assertThat(task.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(task.getJwtSecret()).isEqualTo("task-tok");
        assertThat(task.getKey()).isEqualTo("task-tok");
        assertThat(task.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(task.getPath()).isEqualTo("/api/v1/internal/billing");
    }

    @Test
    @DisplayName("auth=key + key: KEY 形态独立鉴权")
    void keyAuthIndependent() {
        var spi = new TokenGatewayProperties();
        var tb = spi.getTask().getBilling();
        tb.setAuth(AuthType.KEY);
        tb.setKey("task-api-key");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig task = endpoints.taskBilling();
        assertThat(task.getAuth()).isEqualTo(AuthType.KEY);
        assertThat(task.getKey()).isEqualTo("task-api-key");
    }

    @Test
    @DisplayName("反向不污染: 配了 task.billing.url, billing() 仍走通用/legacy 值")
    void taskConfigDoesNotContaminateBilling() {
        var spi = new TokenGatewayProperties();
        spi.getTask().getBilling().setUrl("http://task-billing:9401");
        spi.getTask().getBilling().setPathPrefix("/v1/internal/billing/task");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig generic = endpoints.billing();
        assertThat(generic.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(generic.getPath()).isEqualTo("/api/v1/internal/billing");
        assertThat(generic.getJwtSecret()).isEqualTo("legacy-tok");
    }
}
