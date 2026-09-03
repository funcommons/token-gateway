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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
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
    private fun.commons.tokengateway.relay.FailoverProperties failoverProps;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        upstream = new MockWebServer();
        upstream.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        failoverProps = new fun.commons.tokengateway.relay.FailoverProperties();
        // 退避压到 1ms, 轮换用例不被 1s/2s 退避拖慢
        failoverProps.setBaseBackoffMs(1L);
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
                b,
                failoverProps);
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
        // 本用例专测软失败识别, 关闭轮换保持单渠道语义
        failoverProps.setEnabled(false);
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
        // 本用例专测真实状态码透传, 关闭轮换保持单渠道语义
        failoverProps.setEnabled(false);
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

    private MockResponse jsonOk() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0}");
    }

    /**
     * 路由链 RPC 按真实调用顺序入队: distribute → [scan (仅首轮 prepare)] → preConsume.
     * 轮换轮 (failover) 只 distribute + preConsume, 无 scan.
     */
    private void mockRoute(String channelId, String preConsumeId, boolean withScan) {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"" + channelId + "\","
                        + "\"baseUrl\":\"" + upstream.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"openai\",\"billingMode\":\"BYPASS\"}}"));
        if (withScan) {
            backend.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}"));
        }
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"" + preConsumeId + "\"}}"));
    }

    /**
     * 收集后端 mock 已收到的请求路径 (record-failure / refund 等上报断言用).
     */
    private java.util.List<String> backendPaths() {
        java.util.List<String> paths = new java.util.ArrayList<>();
        okhttp3.mockwebserver.RecordedRequest req;
        try {
            while ((req = backend.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) {
                paths.add(req.getPath());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return paths;
    }

    @Test
    @DisplayName("非流式轮换: 渠道1 500 → recordFailure + refund → 渠道2 成功 (携带 excludeChannelIds)")
    void nonStreamFailoverSucceeds() {
        mockTokenOk();
        mockRoute("c1", "pc-1", true);
        upstream.enqueue(new MockResponse().setResponseCode(500));
        backend.enqueue(jsonOk());                                  // record-failure c1
        backend.enqueue(jsonOk());                                  // refund pc-1
        mockRoute("c2", "pc-2", false);                       // 轮换 re-distribute + preConsume
        mockAuditPass();                                      // 成功后内联输出审查
        backend.enqueue(jsonOk());                                  // settle pc-2 (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"msg_failover\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"));

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("type")).isEqualTo("message");
                })
                .verifyComplete();

        assertThat(upstream.getRequestCount()).isEqualTo(2);
        // 轮换 re-distribute 请求体携带已失败渠道排除名单 (契约对齐 backend excludeChannelIds)
        assertThat(backendPaths()).anyMatch(p -> p != null && p.contains("record-failure"));
    }

    @Test
    @DisplayName("非流式: 上游 400 不可重试 → 不换道 (只调 1 次上游, 终态无 record-failure 双计)")
    void nonStreamBadRequestRecordsFailureWithoutFailover() {
        mockTokenOk();
        mockRoute("c1", "pc-1", true);
        upstream.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"bad\"}"));
        backend.enqueue(jsonOk());                                  // refund pc-1 (终态)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        StepVerifier.create((Mono<?>) controller.messages(null, "sk-ant-x", anthropicBody()))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 400);

        assertThat(upstream.getRequestCount()).isEqualTo(1);
        // 终态失败的健康计数由 AccessLogReporter.reportError 上报, 不应再发 record-failure RPC (防双计)
        assertThat(backendPaths()).noneMatch(p -> p != null && p.contains("record-failure"));
    }

    @Test
    @DisplayName("流式轮换: 渠道1 未吐帧 500 → recordFailure + 渠道2 SSE 成功")
    void streamFailoverSucceeds() {
        mockTokenOk();
        mockRoute("c1", "pc-1", true);
        upstream.enqueue(new MockResponse().setResponseCode(500));
        backend.enqueue(jsonOk());                                  // record-failure c1
        backend.enqueue(jsonOk());                                  // refund pc-1
        mockRoute("c2", "pc-2", false);
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                        + "data: [DONE]\n\n"));

        Map<String, Object> body = anthropicBody();
        body.put("stream", true);

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.messages(null, "sk-ant-x", body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    entityRef.set(entity);
                })
                .verifyComplete();

        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> flux =
                (Flux<ServerSentEvent<String>>) entityRef.get().getBody();
        var events = flux.filter(e -> e.data() != null && !e.data().isBlank())
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
        assertThat(events).anyMatch(e -> e.data().contains("hello"));
        assertThat(upstream.getRequestCount()).isEqualTo(2);
        assertThat(backendPaths()).anyMatch(p -> p != null && p.contains("record-failure"));
    }
}
