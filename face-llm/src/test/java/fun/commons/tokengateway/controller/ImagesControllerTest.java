package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ImagesController 单元测试 (MockWebServer 假主应用 + 假上游).
 *
 * <p>验证:
 * <ul>
 *   <li>成功: validate → distribute → 上游 /v1/images/generations 透传响应</li>
 *   <li>上游失败 → 502 RelayException</li>
 *   <li>缺 token → 401 RelayException</li>
 * </ul>
 */
@DisplayName("ImagesController WebFlux 原生")
class ImagesControllerTest {

    private MockWebServer backendServer;
    private MockWebServer upstreamServer;
    private ImagesController controller;

    @BeforeEach
    void setUp() throws Exception {
        backendServer = new MockWebServer();
        backendServer.start();
        upstreamServer = new MockWebServer();
        upstreamServer.start();

        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backendServer.url("/").toString().replaceAll("/$", ""));
        props.setInternalToken("test-token");
        WebClient.Builder builder = WebClient.builder();
        var tokenApi = new fun.commons.tokengateway.rpc.HttpTokenApi(builder, props,
                new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var channelApi = new fun.commons.tokengateway.rpc.HttpChannelApi(builder, props,
                new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var orchestrator = new fun.commons.tokengateway.relay.RelayOrchestrator(
                tokenApi, channelApi,
                new fun.commons.tokengateway.rpc.HttpBillingApi(builder, props,
                        new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
                        new fun.commons.tokengateway.moderation.ModerationGate(
                        new fun.commons.tokengateway.rpc.HttpModerationApi(builder, props,
                        new fun.commons.tokengateway.rpc.RpcInternalAuth(props))),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
        controller = new ImagesController(
                orchestrator,
                new fun.commons.tokengateway.relay.AccessLogReporter(
                        new fun.commons.tokengateway.rpc.HttpAccessLogApi(builder, props,
                                new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
                        fun.commons.tokengateway.relay.TestChannelHealthReporters.disabled()),
                builder);
    }

    @AfterEach
    void tearDown() throws Exception {
        backendServer.shutdown();
        upstreamServer.shutdown();
    }

    private void mockTokenOk() {
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
    }

    private void mockDistribute() {
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"" + upstreamServer.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"openai\",\"billingMode\":\"BYPASS\"}}"));
        // V087: scan + preConsume 紧跟 distribute 之后
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"pc-1\"}}"));
    }

    @Test
    @DisplayName("成功: 上游 /v1/images/generations 响应透传")
    void generationsPassthrough() throws Exception {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"created\":1720000000,"
                        + "\"data\":[{\"url\":\"https://example.com/a.png\"}]}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "dall-e-3");
        body.put("prompt", "一只猫");
        body.put("n", 1);
        body.put("size", "1024x1024");

        StepVerifier.create(controller.generate("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("created")).isEqualTo(1720000000);
                    assertThat(r.get("data")).isInstanceOf(java.util.List.class);
                })
                .verifyComplete();

        var recorded = upstreamServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/images/generations");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-up");
    }

    @Test
    @DisplayName("缺省 model → 默认 dall-e-3 参与路由")
    void defaultModel() {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"created\":1,\"data\":[]}"));

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", "x");

        StepVerifier.create(controller.generate("Bearer sk-test", null, body))
                .assertNext(entity -> assertThat(entity.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();
    }

    @Test
    @DisplayName("上游失败 → 502 RelayException")
    void upstreamFailure() {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", "x");

        StepVerifier.create((Mono<?>) controller.generate("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 502);
    }

    @Test
    @DisplayName("缺 apiKey → 401 RelayException")
    void missingApiKey() {
        Map<String, Object> body = new HashMap<>();
        body.put("prompt", "x");
        StepVerifier.create((Mono<?>) controller.generate(null, null, body))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }
}
