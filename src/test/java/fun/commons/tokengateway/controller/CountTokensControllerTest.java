package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CountTokensController WebFlux 原生")
class CountTokensControllerTest {

    private MockWebServer backend;
    private MockWebServer upstream;
    private CountTokensController controller;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        upstream = new MockWebServer();
        upstream.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        WebClient.Builder b = WebClient.builder();
        var tokenApi = new HttpTokenApi(b, props, new RpcInternalAuth(props));
        var channelApi = new HttpChannelApi(b, props, new RpcInternalAuth(props));
        controller = new CountTokensController(
                new RelayOrchestrator(tokenApi, channelApi, new HttpBillingApi(b, props, new RpcInternalAuth(props)),
                        new ModerationGate(new fun.commons.tokengateway.rpc.HttpModerationApi(b, props, new RpcInternalAuth(props))),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop()),
                new FormatConverter(), b);
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
        upstream.shutdown();
    }

    private void mockTokenOk() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
    }

    private void mockDistribute(String protocol) {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"" + upstream.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"" + protocol + "\",\"billingMode\":\"BYPASS\"}}"));
        // V087: scan + preConsume 紧跟 distribute 之后 (无 audit, 因 count_tokens 无上游响应)
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"pc-1\"}}"));
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-3");
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        return body;
    }

    @Test
    @DisplayName("Anthropic 上游: 透传 input_tokens 响应")
    void anthropicUpstream() {
        mockTokenOk();
        mockDistribute("anthropic");
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"input_tokens\":42}"));

        StepVerifier.create(controller.countTokens(null, "sk-ant-x", body()))
                .assertNext(r -> assertThat(r.get("input_tokens")).isEqualTo(42))
                .verifyComplete();
    }

    @Test
    @DisplayName("OpenAI 上游 → 501")
    void openaiUpstream501() {
        mockTokenOk();
        mockDistribute("openai");
        StepVerifier.create(controller.countTokens("Bearer sk", null, body()))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 501);
    }

    @Test
    @DisplayName("缺 model → 400")
    void missingModel() {
        StepVerifier.create(controller.countTokens(null, "sk", new LinkedHashMap<>()))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 400);
    }

    @Test
    @DisplayName("缺 apiKey → 401")
    void missingApiKey() {
        StepVerifier.create(controller.countTokens(null, null, body()))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }
}
