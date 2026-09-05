package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpTokenApi 测试: MockWebServer 假主应用, 验证 RPC 请求 + 响应解析 + 超时降级.
 *
 * <p>不依赖 framework4j 任何子模块, 仅 okhttp3 mockwebserver (test scope).
 */
@DisplayName("HttpTokenApi RPC")
class HttpTokenApiTest {

    private MockWebServer server;
    private HttpTokenApi api;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        GatewayProperties props = new GatewayProperties();
        props.setUrl(server.url("/").toString().replaceAll("/$", ""));
        props.setInternalToken("test-internal-token");
        props.setTimeout(Duration.ofSeconds(2));
        api = new HttpTokenApi(WebClient.builder(), new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("validate 成功: 200 ApiResponse 透传 + X-Internal-Token 头")
    void validateSuccess() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"success\",\"data\":{\"valid\":true,\"tokenId\":\"t1\","
                        + "\"userId\":\"u1\",\"tenantId\":\"tn1\"},\"trace_id\":\"x\",\"timestamp\":1}"));

        StepVerifier.create(api.validate(TokenValidateRequest.builder()
                        .apiKey("sk-test").model("gpt-4o").build()))
                .assertNext(resp -> {
                    assertThat(resp.getCode()).isEqualTo(0);
                    assertThat(resp.getData()).isNotNull();
                    assertThat(resp.getData().isValid()).isTrue();
                    assertThat(resp.getData().getTokenId()).isEqualTo("t1");
                })
                .verifyComplete();

        var recorded = server.takeRequest();
        assertThat(recorded.getHeader("X-Internal-Token")).isEqualTo("test-internal-token");
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/tokens/validate");
        assertThat(recorded.getBody().readUtf8()).contains("\"apiKey\":\"sk-test\"");
    }

    @Test
    @DisplayName("validate RPC 失败 (主应用返 500) → onErrorResume 降级为 fail 包络")
    void validateUpstreamError() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("server down"));

        StepVerifier.create(api.validate(TokenValidateRequest.builder().apiKey("sk").build()))
                .assertNext(resp -> {
                    assertThat(resp.isFail()).isTrue();
                    assertThat(resp.getCode()).isEqualTo(ApiCode.SERVICE_TIMEOUT.getCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("validate 超时 → onErrorResume 降级")
    void validateTimeout() {
        server.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":{\"valid\":true}}")
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        StepVerifier.create(api.validate(TokenValidateRequest.builder().apiKey("sk").build()))
                .assertNext(resp -> {
                    assertThat(resp.isFail()).isTrue();
                    assertThat(resp.getCode()).isEqualTo(ApiCode.SERVICE_TIMEOUT.getCode());
                })
                .verifyComplete();
    }
}
