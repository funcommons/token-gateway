package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import fun.commons.tokengateway.spi.config.ResultFilterFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CapabilityEndpoints#resultFilter 回退矩阵 (issue #43, 第 9 面): url/凭证缺省回退
 * backend.* 平移值; timeout 缺省 60s (不回退 backend.timeout); path 缺省
 * /v1/internal/tasks/result-filter; 开关默认关 (回归红线).
 */
@DisplayName("CapabilityEndpoints#resultFilter (issue #43 逐字段回退)")
class CapabilityEndpointsResultFilterTest {

    private static final String BACKEND_URL = "http://backend:9400";

    private static GatewayProperties legacy() {
        var props = new GatewayProperties();
        props.setUrl(BACKEND_URL);
        props.setInternalToken("legacy-tok");
        props.setTimeout(Duration.ofSeconds(10));
        return props;
    }

    @Test
    @DisplayName("全缺省: 开关默认关; url=backend, path=默认端点, timeout=60s (不回退 backend.timeout), auth=jwt(legacy token)")
    void allDefaultsFallBackToBackend() {
        var spi = new TokenGatewayProperties();
        var endpoints = new CapabilityEndpoints(spi, legacy());

        assertThat(spi.getResultFilter().isEnabled()).isFalse();
        EndpointConfig ep = endpoints.resultFilter();
        assertThat(ep.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(ep.getPath()).isEqualTo("/v1/internal/tasks/result-filter");
        assertThat(ep.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(ep.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(ep.getJwtSecret()).isEqualTo("legacy-tok");
        assertThat(ep.getKey()).isEqualTo("legacy-tok");
    }

    @Test
    @DisplayName("backend 无 internal-token: 缺省 auth=none 不带凭证 (localhost/sidecar 形态)")
    void noLegacyTokenMapsToNone() {
        var legacy = legacy();
        legacy.setInternalToken("");
        var endpoints = new CapabilityEndpoints(new TokenGatewayProperties(), legacy);

        EndpointConfig ep = endpoints.resultFilter();
        assertThat(ep.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(ep.getAuth()).isEqualTo(AuthType.NONE);
        assertThat(ep.getJwtSecret()).isNull();
        assertThat(ep.getKey()).isNull();
    }

    @Test
    @DisplayName("独立 url/auth/jwt-secret/timeout/path-prefix: 全取面值")
    void independentFaceConfig() {
        var spi = new TokenGatewayProperties();
        var rf = spi.getResultFilter();
        rf.setEnabled(true);
        rf.setUrl("http://mmagix:9500");
        rf.setPathPrefix("/v1/internal/tasks/result-filter");
        rf.setAuth(AuthType.JWT);
        rf.setJwtSecret("rf-s3cret");
        rf.setTimeout(Duration.ofSeconds(90));
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig ep = endpoints.resultFilter();
        assertThat(ep.getUrl()).isEqualTo("http://mmagix:9500");
        assertThat(ep.getPath()).isEqualTo("/v1/internal/tasks/result-filter");
        assertThat(ep.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(ep.getJwtSecret()).isEqualTo("rf-s3cret");
        assertThat(ep.getTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("仅 internal-token (auth 未配): 平移语义映射 jwt, url/timeout/path 仍缺省回退")
    void internalTokenAloneMapsToJwt() {
        var spi = new TokenGatewayProperties();
        spi.getResultFilter().setInternalToken("rf-tok");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig ep = endpoints.resultFilter();
        assertThat(ep.getAuth()).isEqualTo(AuthType.JWT);
        assertThat(ep.getJwtSecret()).isEqualTo("rf-tok");
        assertThat(ep.getKey()).isEqualTo("rf-tok");
        assertThat(ep.getUrl()).isEqualTo(BACKEND_URL);
        assertThat(ep.getPath()).isEqualTo("/v1/internal/tasks/result-filter");
        assertThat(ep.getTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("auth=key + key: KEY 形态独立鉴权")
    void keyAuthIndependent() {
        var spi = new TokenGatewayProperties();
        var rf = spi.getResultFilter();
        rf.setAuth(AuthType.KEY);
        rf.setKey("rf-api-key");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        EndpointConfig ep = endpoints.resultFilter();
        assertThat(ep.getAuth()).isEqualTo(AuthType.KEY);
        assertThat(ep.getKey()).isEqualTo("rf-api-key");
    }

    @Test
    @DisplayName("自定义 path-prefix 覆盖默认端点; max-attempts 默认 5 可配")
    void pathPrefixOverrideAndMaxAttempts() {
        var spi = new TokenGatewayProperties();
        spi.getResultFilter().setPathPrefix("/api/v1/internal/tasks/result-filter");
        assertThat(spi.getResultFilter().getMaxAttempts()).isEqualTo(5);
        spi.getResultFilter().setMaxAttempts(3);
        var endpoints = new CapabilityEndpoints(spi, legacy());

        assertThat(endpoints.resultFilter().getPath())
                .isEqualTo("/api/v1/internal/tasks/result-filter");
        assertThat(spi.getResultFilter().getMaxAttempts()).isEqualTo(3);
        assertThat(ResultFilterFaceConfig.DEFAULT_PATH).isEqualTo("/v1/internal/tasks/result-filter");
    }

    @Test
    @DisplayName("反向不污染: 配了 result-filter.url, billing()/taskBilling() 仍走既有值")
    void resultFilterDoesNotContaminateOtherFaces() {
        var spi = new TokenGatewayProperties();
        spi.getResultFilter().setEnabled(true);
        spi.getResultFilter().setUrl("http://mmagix:9500");
        spi.getResultFilter().setJwtSecret("rf-s3cret");
        var endpoints = new CapabilityEndpoints(spi, legacy());

        assertThat(endpoints.billing().getUrl()).isEqualTo(BACKEND_URL);
        assertThat(endpoints.billing().getJwtSecret()).isEqualTo("legacy-tok");
        assertThat(endpoints.taskBilling().getUrl()).isEqualTo(BACKEND_URL);
    }
}
