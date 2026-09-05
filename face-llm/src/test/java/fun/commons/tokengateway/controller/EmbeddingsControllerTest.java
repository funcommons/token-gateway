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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EmbeddingsController 单元测试 (MockWebServer 假主应用 + 假上游).
 *
 * <p>验证:
 * <ul>
 *   <li>成功: validate → distribute → 上游 /v1/embeddings 透传响应</li>
 *   <li>上游失败 → 502 RelayException</li>
 *   <li>缺 token → 401 RelayException</li>
 * </ul>
 */
@DisplayName("EmbeddingsController WebFlux 原生")
class EmbeddingsControllerTest {

    private MockWebServer backendServer;
    private MockWebServer upstreamServer;
    private EmbeddingsController controller;

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
        var tokenApi = new fun.commons.tokengateway.rpc.HttpTokenApi(builder, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var channelApi = new fun.commons.tokengateway.rpc.HttpChannelApi(builder, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var orchestrator = new fun.commons.tokengateway.relay.RelayOrchestrator(
                tokenApi, channelApi,
                new fun.commons.tokengateway.rpc.HttpBillingApi(builder, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
                        new fun.commons.tokengateway.moderation.ModerationGate(
                        new fun.commons.tokengateway.rpc.HttpModerationApi(builder, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props), new fun.commons.tokengateway.spi.config.TokenGatewayProperties())),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
        controller = new EmbeddingsController(
                orchestrator,
                new fun.commons.tokengateway.relay.AccessLogReporter(
                        new fun.commons.tokengateway.rpc.HttpAccessLogApi(builder, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
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
    @DisplayName("成功: 上游 /v1/embeddings 响应透传")
    void embeddingsPassthrough() throws Exception {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,"
                        + "\"embedding\":[0.1,0.2]}],\"model\":\"text-embedding-3-small\","
                        + "\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2}}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "text-embedding-3-small");
        body.put("input", "你好世界");

        StepVerifier.create(controller.embeddings("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("object")).isEqualTo("list");
                    assertThat(r.get("model")).isEqualTo("text-embedding-3-small");
                })
                .verifyComplete();

        var recorded = upstreamServer.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/embeddings");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-up");
    }

    @Test
    @DisplayName("input 为数组: 正常透传")
    void embeddingsListInput() {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"object\":\"list\",\"data\":[],"
                        + "\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"));

        Map<String, Object> body = new HashMap<>();
        body.put("input", List.of("a", "b"));

        StepVerifier.create(controller.embeddings("Bearer sk-test", null, body))
                .assertNext(entity -> assertThat(entity.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();
    }

    @Test
    @DisplayName("上游 500 → RelayException(500) 真实状态码透传 (issue #1 缺口3)")
    void upstreamFailure() {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        Map<String, Object> body = new HashMap<>();
        body.put("input", "x");

        StepVerifier.create((Mono<?>) controller.embeddings("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 500
                        && re.getMessage().contains("HTTP_500"));
    }

    @Test
    @DisplayName("上游 429 → RelayException(429) 真实状态码 (不再恒 502, issue #1 缺口3)")
    void upstream429PassesThroughRealStatus() {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse().setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"rate limit exceeded\"}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        Map<String, Object> body = new HashMap<>();
        body.put("input", "x");

        StepVerifier.create((Mono<?>) controller.embeddings("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 429
                        && re.getMessage().contains("HTTP_429"));
    }

    @Test
    @DisplayName("软失败: 200+错误载荷 → RelayException(403) 而非成功透传 (issue #1 缺口1)")
    void upstreamSoftErrorPayload() {
        mockTokenOk();
        mockDistribute();
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"quota exceeded for project\",\"status\":403}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        Map<String, Object> body = new HashMap<>();
        body.put("input", "x");

        StepVerifier.create((Mono<?>) controller.embeddings("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 403
                        && re.getMessage().contains("quota exceeded for project"));
    }

    @Test
    @DisplayName("缺 apiKey → 401 RelayException")
    void missingApiKey() {
        Map<String, Object> body = new HashMap<>();
        body.put("input", "x");
        StepVerifier.create((Mono<?>) controller.embeddings(null, null, body))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }
}
