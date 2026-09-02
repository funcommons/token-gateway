package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.upstream.SsePassthroughInvoker;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MessagesController 端到端单元测试 (MockWebServer).
 *
 * <p>验证 anthropic 客户端 → anthropic/openai 上游两种场景 + 参数校验.
 */
@DisplayName("MessagesController WebFlux 原生")
class MessagesControllerTest {

    private MockWebServer backend;
    private MockWebServer upstream;
    private MessagesController controller;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        upstream = new MockWebServer();
        upstream.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        WebClient.Builder b = WebClient.builder();
        var tokenApi = new fun.commons.tokengateway.rpc.HttpTokenApi(b, props, new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var channelApi = new fun.commons.tokengateway.rpc.HttpChannelApi(b, props, new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var orchestrator = new RelayOrchestrator(tokenApi, channelApi, new HttpBillingApi(b, props, new RpcInternalAuth(props)),
                new ModerationGate(new fun.commons.tokengateway.rpc.HttpModerationApi(b, props, new RpcInternalAuth(props))),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
        controller = new MessagesController(
                orchestrator,
                new SsePassthroughInvoker(b),
                new FormatConverter(),
                new fun.commons.tokengateway.relay.AccessLogReporter(
                        new fun.commons.tokengateway.rpc.HttpAccessLogApi(b, props,
                                new RpcInternalAuth(props)),
                        fun.commons.tokengateway.relay.TestChannelHealthReporters.disabled()),
                new fun.commons.tokengateway.rpc.HttpModerationApi(b, props, new RpcInternalAuth(props)),
                b);
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
    }

    private void mockScanPass() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}"));
        // V087: preConsume 紧跟 scan 之后 (moderation pass → preConsume → upstream)
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"pc-1\",\"estimatedQuota\":10}}"));
    }

    private void mockAuditPass() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\",\"source\":\"NONE\"}}"));
    }

    private Map<String, Object> anthropicBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-3-5-sonnet");
        body.put("max_tokens", 256);
        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", "你好");
        body.put("messages", List.of(userMsg));
        return body;
    }

    @Test
    @DisplayName("软失败: openai 上游 200+错误载荷 (无 status) → RelayException(502) 而非垃圾 200 (issue #1 缺口1)")
    void upstreamSoftErrorPayload() {
        mockTokenOk();
        mockDistribute("openai");
        mockScanPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"model overloaded\","
                        + "\"type\":\"server_error\",\"code\":\"overloaded_error\"}}"));
        // 错误路径 fire-and-forget: refund + access-log
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        StepVerifier.create((Mono<?>) controller.messages("Bearer sk-test", null, anthropicBody()))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502
                        && re.getMessage().contains("model overloaded"));
    }

    @Test
    @DisplayName("上游 401 → RelayException(401) 真实状态码 (不再恒 502, issue #1 缺口3)")
    void upstream401PassesThroughRealStatus() {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        upstream.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                        + "\"message\":\"invalid x-api-key\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        StepVerifier.create((Mono<?>) controller.messages("Bearer sk-test", null, anthropicBody()))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 401
                        && re.getMessage().contains("HTTP_401"));
    }

    @Test
    @DisplayName("非流式 anthropic 上游: 原样透传 (type=message shape)")
    void nonStreamAnthropicPassthrough() {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        mockAuditPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"));

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("type")).isEqualTo("message");
                    assertThat(r.get("id")).isEqualTo("msg_1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("非流式 openai 上游: 响应转 anthropic shape (type=message)")
    void nonStreamOpenaiTranslated() {
        mockTokenOk();
        mockDistribute("openai");
        mockScanPass();
        mockAuditPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("type")).isEqualTo("message");
                    assertThat(r.get("stop_reason")).isEqualTo("end_turn");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("缺 model → 400 RelayException")
    void missingModel() {
        Map<String, Object> body = new HashMap<>();
        body.put("max_tokens", 256);
        try {
            controller.messages(null, "sk-ant-x", body);
        } catch (RelayException e) {
            assertThat(e.getHttpStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("缺 max_tokens → 400 RelayException")
    void missingMaxTokens() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-3");
        try {
            controller.messages(null, "sk-ant-x", body);
        } catch (RelayException e) {
            assertThat(e.getHttpStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("缺 apiKey → 401 RelayException")
    void missingApiKey() {
        StepVerifier.create((Mono<?>) controller.messages(null, null, anthropicBody()))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("OpenAI 上游 + cache_control → 静默剥离 (走 anthropicToOpenAiBody), 不再 400")
    void openaiUpstreamCacheControlStripped() throws Exception {
        mockTokenOk();
        mockDistribute("openai");
        mockScanPass();
        mockAuditPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));

        Map<String, Object> body = anthropicBody();
        body.put("system", Map.of("type", "text", "text", "sys",
                "cache_control", Map.of("type", "ephemeral")));

        StepVerifier.create(controller.messages(null, "sk-ant-x", body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("type")).isEqualTo("message");
                })
                .verifyComplete();

        // 验证转发给上游的 body 不含 cache_control (被 anthropicToOpenAiBody 剥离)
        String upstreamReq = upstream.takeRequest().getBody().readUtf8();
        assertThat(upstreamReq).doesNotContain("cache_control");
    }
}
