package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.config.ClientIpProperties;
import fun.commons.tokengateway.util.ClientIpResolver;

import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.upstream.SsePassthroughInvoker;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
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
    private final java.util.List<String> healthCalls = new java.util.ArrayList<>();
    /** clientIp 信任策略 (issue #27 透传用例动态调整 trusted-proxies). */
    private ClientIpProperties clientIpProps;

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
        clientIpProps = new ClientIpProperties();
        WebClient.Builder b = WebClient.builder();
        var tokenApi = new fun.commons.tokengateway.rpc.HttpTokenApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var channelApi = new fun.commons.tokengateway.rpc.HttpChannelApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var orchestrator = new RelayOrchestrator(tokenApi, channelApi, new HttpBillingApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new ModerationGate(new fun.commons.tokengateway.rpc.HttpModerationApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props), new fun.commons.tokengateway.spi.config.TokenGatewayProperties())),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
        controller = new MessagesController(
                orchestrator,
                new SsePassthroughInvoker(b),
                new FormatConverter(),
                new fun.commons.tokengateway.relay.AccessLogReporter(
                        new fun.commons.tokengateway.rpc.HttpAccessLogApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                        fun.commons.tokengateway.relay.TestChannelHealthReporters.recording(healthCalls)),
                new fun.commons.tokengateway.rpc.HttpModerationApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props), new fun.commons.tokengateway.spi.config.TokenGatewayProperties()),
                b,
                failoverProps,
                new ClientIpResolver(clientIpProps),
                new fun.commons.tokengateway.config.UpstreamPassthroughProperties());
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

        StepVerifier.create((Mono<?>) controller.messages("Bearer sk-test", null, anthropicBody(), exchange()))
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

        StepVerifier.create((Mono<?>) controller.messages("Bearer sk-test", null, anthropicBody(), exchange()))
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

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody(), exchange()))
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
    @DisplayName("issue #26 非流式 anthropic 上游: usage 细分随 settle 下发 (cache_creation 独立成维, 不再并账 cacheRead)")
    void nonStreamAnthropicSettleCarriesBreakdown() throws Exception {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        mockAuditPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],"
                        + "\"usage\":{\"input_tokens\":36,\"output_tokens\":16,"
                        + "\"cache_read_input_tokens\":128,\"cache_creation_input_tokens\":64}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody(), exchange()))
                .assertNext(entity -> assertThat(entity.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();

        String settleBody = null;
        String accessLogBody = null;
        for (int i = 0; i < 8 && (settleBody == null || accessLogBody == null); i++) {
            var recorded = backend.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS);
            if (recorded == null) {
                break;
            }
            if (recorded.getPath().contains("/billing/settle")) {
                settleBody = recorded.getBody().readUtf8();
            } else if (recorded.getPath().contains("/access-log/record")) {
                accessLogBody = recorded.getBody().readUtf8();
            }
        }
        assertThat(settleBody).isNotNull();
        assertThat(settleBody).contains("\"actualPromptTokens\":36");
        assertThat(settleBody).contains("\"actualCompletionTokens\":16");
        // 口径定版: read → cacheReadTokens=128 (旧口径曾把 creation 并入成 128+64)
        assertThat(settleBody).contains("\"cacheReadTokens\":128");
        assertThat(settleBody).contains("\"cacheCreationTokens\":64");
        // issue #35: 非流式 usage 取自上游响应体 (实测) → 双侧 UPSTREAM
        assertThat(settleBody).contains("\"usageSource\":\"UPSTREAM\"");
        assertThat(accessLogBody).as("access-log body").isNotNull();
        assertThat(accessLogBody).contains("\"usageSource\":\"UPSTREAM\"");
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

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody(), exchange()))
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
            controller.messages(null, "sk-ant-x", body, exchange());
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
            controller.messages(null, "sk-ant-x", body, exchange());
        } catch (RelayException e) {
            assertThat(e.getHttpStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("缺 apiKey → 401 RelayException")
    void missingApiKey() {
        StepVerifier.create((Mono<?>) controller.messages(null, null, anthropicBody(), exchange()))
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

        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
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
        backend.enqueue(jsonOk());                                  // refund pc-1 (failover 内)
        mockRoute("c2", "pc-2", false);                       // 轮换 re-distribute + preConsume
        backend.enqueue(jsonOk());                                  // record-failure c1 (换道成功后补记)
        mockAuditPass();                                      // 成功后内联输出审查
        backend.enqueue(jsonOk());                                  // settle pc-2 (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"msg_failover\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"));

        StepVerifier.create(controller.messages(null, "sk-ant-x", anthropicBody(), exchange()))
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

        StepVerifier.create((Mono<?>) controller.messages(null, "sk-ant-x", anthropicBody(), exchange()))
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
        backend.enqueue(jsonOk());                                  // refund pc-1 (failover 内)
        mockRoute("c2", "pc-2", false);
        backend.enqueue(jsonOk());                                  // record-failure c1 (换道成功后补记)
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
        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
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

    @Test
    @DisplayName("流式 anthropic 上游无 usage: completion 按已吐 text_delta chars/4 估算, 不再固定 256 (issue #33)")
    void streamAnthropicNativeNoUsageSettlesByEmittedChars() throws Exception {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        // "abcd" (4) + "efgh" (4) = 8 chars → completion = 8/4 = 2 (旧逻辑固定 256)
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("event: message_start\ndata: {\"type\":\"message_start\","
                        + "\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[]}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"abcd\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"efgh\"}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));
        backend.enqueue(jsonOk());                                  // settle (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        Map<String, Object> body = anthropicBody();
        body.put("stream", true);

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
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
        assertThat(events).isNotEmpty();

        String settleBody = settleBodyAfterDrain();
        assertThat(settleBody).isNotNull();
        // prompt: "你好" len/4=0 → max(1,0)=1; completion: 8 chars/4 = 2
        assertThat(settleBody).contains("\"actualPromptTokens\":1");
        assertThat(settleBody).contains("\"actualCompletionTokens\":2");
        assertThat(settleBody).doesNotContain("\"actualCompletionTokens\":256");
    }

    @Test
    @DisplayName("issue #35 流式无 usage: settle 与 access-log 双侧 usageSource=ESTIMATED (估算兜底真相位)")
    void streamNoUsageMarksEstimatedOnBothSides() throws Exception {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        // "abcd" (4) + "efgh" (4) = 8 chars → completion = 8/4 = 2
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("event: message_start\ndata: {\"type\":\"message_start\","
                        + "\"message\":{\"id\":\"m1\",\"role\":\"assistant\",\"content\":[]}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"abcd\"}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"efgh\"}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));
        backend.enqueue(jsonOk());                                  // settle (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        Map<String, Object> body = anthropicBody();
        body.put("stream", true);

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    entityRef.set(entity);
                })
                .verifyComplete();

        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> flux =
                (Flux<ServerSentEvent<String>>) entityRef.get().getBody();
        flux.filter(e -> e.data() != null && !e.data().isBlank())
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        String[] bodies = settleAndAccessLogAfterDrain();
        assertThat(bodies[0]).as("settle body").isNotNull();
        assertThat(bodies[0]).contains("\"usageSource\":\"ESTIMATED\"");
        assertThat(bodies[0]).contains("\"actualCompletionTokens\":2");
        assertThat(bodies[1]).as("access-log body").isNotNull();
        assertThat(bodies[1]).contains("\"usageSource\":\"ESTIMATED\"");
    }

    @Test
    @DisplayName("流式 openai 上游 (Anthropic→OpenAI 转换) 无 usage: completion 按 delta.content chars/4 估算 (issue #33)")
    void streamOpenaiUpstreamNoUsageSettlesByEmittedChars() throws Exception {
        mockTokenOk();
        mockDistribute("openai");
        mockScanPass();
        // 喂给累加器的是上游原始 OpenAI 形状: "Hello," (6) + " world!" (7) = 13 chars → 13/4 = 3
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"Hello,\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\" world!\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"));
        backend.enqueue(jsonOk());                                  // settle (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        Map<String, Object> body = anthropicBody();
        body.put("stream", true);

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
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
        assertThat(events).isNotEmpty();

        String settleBody = settleBodyAfterDrain();
        assertThat(settleBody).isNotNull();
        assertThat(settleBody).contains("\"actualPromptTokens\":1");
        assertThat(settleBody).contains("\"actualCompletionTokens\":3");
        assertThat(settleBody).doesNotContain("\"actualCompletionTokens\":256");
    }

    @Test
    @DisplayName("流式 anthropic 上游有 usage: 真实 output_tokens 计费, 已吐 chars 不参与 (issue #33 回归红线)")
    void streamAnthropicNativeWithUsageRegression() throws Exception {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("event: message_start\ndata: {\"type\":\"message_start\","
                        + "\"message\":{\"usage\":{\"input_tokens\":25,\"cache_read_input_tokens\":3}}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello world\"}}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\","
                        + "\"usage\":{\"output_tokens\":9}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));
        backend.enqueue(jsonOk());                                  // settle (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        Map<String, Object> body = anthropicBody();
        body.put("stream", true);

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    entityRef.set(entity);
                })
                .verifyComplete();

        // 订阅并排空响应体 Flux —— 不订阅则上游永不触发、settle 永不落 (与兄弟用例同款)
        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> flux =
                (Flux<ServerSentEvent<String>>) entityRef.get().getBody();
        flux.filter(e -> e.data() != null && !e.data().isBlank())
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        String settleBody = settleBodyAfterDrain();
        assertThat(settleBody).isNotNull();
        // 红线: 有真实 usage 时按真实值结算 (已吐 11 chars 不参与任何计算)
        assertThat(settleBody).contains("\"actualPromptTokens\":25");
        assertThat(settleBody).contains("\"actualCompletionTokens\":9");
        assertThat(settleBody).contains("\"cacheReadTokens\":3");
        // issue #35: 有 usage 帧 = 上游实测真相位
        assertThat(settleBody).contains("\"usageSource\":\"UPSTREAM\"");
    }

    @Test
    @DisplayName("issue #35 流式有 usage: settle 与 access-log 双侧 usageSource=UPSTREAM (实测真相位)")
    void streamWithUsageMarksUpstreamOnBothSides() throws Exception {
        mockTokenOk();
        mockDistribute("anthropic");
        mockScanPass();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("event: message_start\ndata: {\"type\":\"message_start\","
                        + "\"message\":{\"usage\":{\"input_tokens\":25,\"cache_read_input_tokens\":3}}}\n\n"
                        + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\","
                        + "\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello world\"}}\n\n"
                        + "event: message_delta\ndata: {\"type\":\"message_delta\","
                        + "\"usage\":{\"output_tokens\":9}}\n\n"
                        + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));
        backend.enqueue(jsonOk());                                  // settle (fire-and-forget)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        Map<String, Object> body = anthropicBody();
        body.put("stream", true);

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.messages(null, "sk-ant-x", body, exchange()))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    entityRef.set(entity);
                })
                .verifyComplete();

        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> flux =
                (Flux<ServerSentEvent<String>>) entityRef.get().getBody();
        flux.filter(e -> e.data() != null && !e.data().isBlank())
                .collectList()
                .block(java.time.Duration.ofSeconds(10));

        String[] bodies = settleAndAccessLogAfterDrain();
        assertThat(bodies[0]).as("settle body").isNotNull();
        assertThat(bodies[0]).contains("\"usageSource\":\"UPSTREAM\"");
        assertThat(bodies[0]).contains("\"actualPromptTokens\":25");
        assertThat(bodies[1]).as("access-log body").isNotNull();
        assertThat(bodies[1]).contains("\"usageSource\":\"UPSTREAM\"");
    }

    /**
     * 轮询后端请求取 settle 请求体 (跳过 validate/scan/preConsume 等, 上限 ~18s).
     */
    private String settleBodyAfterDrain() throws InterruptedException {
        for (int i = 0; i < 6; i++) {
            RecordedRequest recorded = backend.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS);
            if (recorded == null) {
                break;
            }
            if (recorded.getPath().contains("/billing/settle")) {
                return recorded.getBody().readUtf8();
            }
        }
        return null;
    }

    /**
     * 轮询后端请求同时取 settle + access-log 请求体 (issue #35 双侧断言用;
     * [0] = settle body, [1] = access-log body, 未捕获为 null).
     */
    private String[] settleAndAccessLogAfterDrain() throws InterruptedException {
        String[] bodies = new String[2];
        for (int i = 0; i < 8 && (bodies[0] == null || bodies[1] == null); i++) {
            RecordedRequest recorded = backend.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS);
            if (recorded == null) {
                break;
            }
            if (recorded.getPath().contains("/billing/settle")) {
                bodies[0] = recorded.getBody().readUtf8();
            } else if (recorded.getPath().contains("/access-log/record")) {
                bodies[1] = recorded.getBody().readUtf8();
            }
        }
        return bodies;
    }

    /** fire-and-forget 健康上报异步完成, 轮询等待 (上限 5s). */
    private void waitForHealthCall() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (healthCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("轮换无候选 (单渠道): 500 可重试但换道中止 → 不中间上报, 终态 reportError 恰计 1 次 (防双计)")
    void nonStreamFailoverNoCandidateRecordsSingleFailure() throws Exception {
        mockTokenOk();
        mockRoute("c1", "pc-1", true);
        upstream.enqueue(new MockResponse().setResponseCode(500));
        backend.enqueue(jsonOk());                                  // refund pc-1 (failover 内)
        backend.enqueue(new MockResponse()                          // distribute: 无可用渠道 (业务失败信封)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"无可用渠道\",\"data\":null}"));
        backend.enqueue(jsonOk());                                  // 终态 refund pc-1 (幂等)
        backend.enqueue(jsonOk());                                  // access-log (fire-and-forget)

        StepVerifier.create((Mono<?>) controller.messages(null, "sk-ant-x", anthropicBody(), exchange()))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 500);

        waitForHealthCall();
        assertThat(upstream.getRequestCount()).isEqualTo(1);
        // 轮换中止时不再中间上报 record-failure, 该失败只由终态 reportError 计一次
        assertThat(backendPaths()).noneMatch(p -> p != null && p.contains("record-failure"));
        assertThat(healthCalls.stream().filter(c -> c.startsWith("failure:c1")).count()).isEqualTo(1L);
    }

    // ===== clientIp 透传 (issue #27): controller → validate RPC 端到端 =====

    /** 指定对端地址的 exchange (无 XFF). */
    private static ServerWebExchange exchangeAt(String remoteIp) {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/messages")
                .remoteAddress(new InetSocketAddress(remoteIp, 51000)));
    }

    /** 无效凭证最短路径: 只有 token-validate 一次 RPC, 便于 takeRequest 断言请求体. */
    private void enqueueInvalidToken() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":false}}"));
    }

    private RecordedRequest takenValidateRequest() throws InterruptedException {
        RecordedRequest req = backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).contains("/api/v1/internal/tokens/validate");
        return req;
    }

    @Test
    @DisplayName("clientIp 透传 (issue #27): 无 XFF + trusted=0 → validate 请求体 = TCP 对端地址")
    void clientIpPassesThroughRemoteAddress() throws Exception {
        enqueueInvalidToken();
        StepVerifier.create((Mono<?>) controller.messages(null, "sk-ant-x", anthropicBody(),
                        exchangeAt("10.9.8.7")))
                .verifyErrorMatches(e -> e instanceof RelayException re && re.getHttpStatus() == 401);
        assertThat(takenValidateRequest().getBody().readUtf8())
                .contains("\"clientIp\":\"10.9.8.7\"");
    }

    @Test
    @DisplayName("clientIp 防伪造 (issue #27): trusted=0 时伪造首位 XFF 不采信, 恒取 TCP 对端")
    void clientIpIgnoresSpoofedXffWhenUntrusted() throws Exception {
        enqueueInvalidToken();
        MockServerWebExchange spoofed = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/messages")
                        .remoteAddress(new InetSocketAddress("10.9.8.7", 51000))
                        .header("X-Forwarded-For", "1.2.3.4"));
        StepVerifier.create((Mono<?>) controller.messages(null, "sk-ant-x", anthropicBody(), spoofed))
                .verifyErrorMatches(e -> e instanceof RelayException re && re.getHttpStatus() == 401);
        assertThat(takenValidateRequest().getBody().readUtf8())
                .contains("\"clientIp\":\"10.9.8.7\"")
                .doesNotContain("1.2.3.4");
    }

    @Test
    @DisplayName("clientIp 信任代理 (issue #27): trusted=1 → XFF 从右跳 1 取真实客户端")
    void clientIpTrustedProxyTakesXff() throws Exception {
        clientIpProps.setTrustedProxies(1);
        enqueueInvalidToken();
        MockServerWebExchange proxied = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/messages")
                        .remoteAddress(new InetSocketAddress("172.17.0.9", 51000))
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1"));
        StepVerifier.create((Mono<?>) controller.messages(null, "sk-ant-x", anthropicBody(), proxied))
                .verifyErrorMatches(e -> e instanceof RelayException re && re.getHttpStatus() == 401);
        assertThat(takenValidateRequest().getBody().readUtf8())
                .contains("\"clientIp\":\"10.0.0.1\"")
                .doesNotContain("1.2.3.4");
    }

    /** 直调注入 exchange (clientIp 解析入口; 缺省无 XFF → 取 mock 对端地址). */
    private static ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/v1/test"));
    }
}
