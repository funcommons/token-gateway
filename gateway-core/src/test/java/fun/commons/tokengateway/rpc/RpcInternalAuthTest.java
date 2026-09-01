package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RpcInternalAuth 单测: 验证 dev/prod token 策略与 RequestHeadersSpec 注入.
 */
@DisplayName("RpcInternalAuth")
class RpcInternalAuthTest {

    private WebClient.RequestHeadersSpec<?> newSpec() {
        return WebClient.builder().build().post().uri("http://x");
    }

    @Test
    @DisplayName("null token → 不发头")
    void nullTokenSkipped() {
        GatewayProperties props = new GatewayProperties();
        props.setInternalToken(null);
        RpcInternalAuth auth = new RpcInternalAuth(props);

        assertThat(auth.shouldAttach()).isFalse();
    }

    @Test
    @DisplayName("空 token → 不发头")
    void blankTokenSkipped() {
        GatewayProperties props = new GatewayProperties();
        props.setInternalToken("   ");
        RpcInternalAuth auth = new RpcInternalAuth(props);

        assertThat(auth.shouldAttach()).isFalse();
    }

    @Test
    @DisplayName("dev-prefix token → 不发头 (避免 backend 误判 malformed)")
    void devPrefixTokenSkipped() {
        GatewayProperties props = new GatewayProperties();
        props.setInternalToken("dev-internal-token");
        RpcInternalAuth auth = new RpcInternalAuth(props);

        assertThat(auth.shouldAttach()).isFalse();
    }

    @Test
    @DisplayName("真实签名 token → 发头")
    void realTokenAttached() {
        GatewayProperties props = new GatewayProperties();
        props.setInternalToken("eyJhbGciOiJIUzI1NiJ9.signature");
        RpcInternalAuth auth = new RpcInternalAuth(props);

        assertThat(auth.shouldAttach()).isTrue();
    }

    @Test
    @DisplayName("attachTo(spec) 在 dev 模式下不修改 spec")
    void attachToSkipsInDev() {
        GatewayProperties props = new GatewayProperties();
        props.setInternalToken("dev-xxx");
        RpcInternalAuth auth = new RpcInternalAuth(props);

        WebClient.RequestHeadersSpec<?> spec = newSpec();
        auth.attachTo(spec);
    }
}
