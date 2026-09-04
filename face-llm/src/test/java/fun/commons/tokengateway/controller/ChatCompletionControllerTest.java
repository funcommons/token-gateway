package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.moderation.ModerationOutcome;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChatCompletionController 端到端单元测试 (MockWebServer 假主应用 + 假上游).
 *
 * <p>验证:
 * <ul>
 *   <li>非流式: validate → distribute → 上游调用 → anthropic→openai 响应转换</li>
 *   <li>流式: validate → distribute → 上游 SSE 流透传</li>
 *   <li>缺 token → 401 RelayException</li>
 *   <li>token 无效 → 401</li>
 * </ul>
 */
@DisplayName("ChatCompletionController WebFlux 原生")
class ChatCompletionControllerTest {

    private MockWebServer backendServer;
    private MockWebServer upstreamServer;
    private ChatCompletionController controller;
    private MockModerationApi moderationApi;
    private fun.commons.tokengateway.relay.FailoverProperties failoverProps;
    private final java.util.List<String> healthCalls = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        backendServer = new MockWebServer();
        backendServer.start();
        upstreamServer = new MockWebServer();
        upstreamServer.start();

        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backendServer.url("/").toString().replaceAll("/$", ""));
        props.setInternalToken("test-token");
        failoverProps = new fun.commons.tokengateway.relay.FailoverProperties();
        // 退避压到 1ms, 轮换用例不被 1s/2s 退避拖慢
        failoverProps.setBaseBackoffMs(1L);
        WebClient.Builder builder = WebClient.builder();
        var tokenApi = new fun.commons.tokengateway.rpc.HttpTokenApi(builder, props, new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        var channelApi = new fun.commons.tokengateway.rpc.HttpChannelApi(builder, props, new fun.commons.tokengateway.rpc.RpcInternalAuth(props));
        moderationApi = new MockModerationApi();
        var moderationGate = new ModerationGate(moderationApi);
        var orchestrator = new fun.commons.tokengateway.relay.RelayOrchestrator(
                tokenApi, channelApi,
                new fun.commons.tokengateway.rpc.HttpBillingApi(builder, props,
                        new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
                moderationGate,
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
        controller = new ChatCompletionController(
                orchestrator,
                new fun.commons.tokengateway.upstream.SsePassthroughInvoker(builder),
                new fun.commons.tokengateway.format.FormatConverter(),
                new fun.commons.tokengateway.relay.AccessLogReporter(
                        new fun.commons.tokengateway.rpc.HttpAccessLogApi(builder, props,
                                new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
                        fun.commons.tokengateway.relay.TestChannelHealthReporters.recording(healthCalls)),
                moderationApi,
                builder,
                failoverProps);
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

    private void mockDistribute(String protocol) {
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"" + upstreamServer.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"" + protocol + "\",\"billingMode\":\"BYPASS\"}}"));
        // V087: preConsume 紧跟 distribute 之后 (moderation 走 MockApi 无 RPC)
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"pc-1\"}}"));
    }

    @Test
    @DisplayName("非流式 OpenAI 上游: 透传响应")
    void nonStreamOpenaiPassthrough() {
        mockTokenOk();
        mockDistribute("openai");
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "x")));

        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("object")).isEqualTo("chat.completion");
                    assertThat(r.get("id")).isEqualTo("chatcmpl-1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("非流式 anthropic 上游: 响应转 OpenAI shape")
    void nonStreamAnthropicTranslated() {
        mockTokenOk();
        mockDistribute("anthropic");
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"你好\"}],"
                        + "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-3");
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("object")).isEqualTo("chat.completion");
                    assertThat(r.get("id")).asString().startsWith("anthropic-");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("缺 apiKey → 401 RelayException")
    void missingApiKey() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of());
        StepVerifier.create((Mono<?>) controller.complete(null, null, body))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("token 校验失败 → 401")
    void tokenInvalid() {
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":false}}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of());

        StepVerifier.create((Mono<?>) controller.complete("Bearer bad", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("软失败: 上游 200+错误载荷 → RelayException(429) + 渠道记失败 + 退款 (issue #1 缺口1)")
    void upstreamSoftErrorPayload() throws Exception {
        // 本用例专测软失败识别, 关闭轮换保持单渠道语义
        failoverProps.setEnabled(false);
        mockTokenOk();
        // PLATFORM 账本: 计费 RPC 真实发生, 才能断言 refund-vs-settle 走向
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"" + upstreamServer.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"openai\",\"billingMode\":\"PLATFORM\"}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"pc-1\"}}"));
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"You exceeded your current quota\","
                        + "\"type\":\"insufficient_quota\",\"status\":429}}"));
        // 错误路径 fire-and-forget: refund + access-log (record-failure 走 recording 桩不发 HTTP)
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "x")));

        StepVerifier.create((Mono<?>) controller.complete("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 429
                        && re.getMessage().contains("You exceeded your current quota"));

        waitForHealthCall();
        // 渠道健康: 记失败携真实 errorCode, 不再走 reportSuccess 清零
        assertThat(healthCalls).containsExactly("failure:c1:HTTP_429");

        // 计费: refund 发生, settle 不发生 (软失败不得按成功结算).
        // fire-and-forget 出网有延迟, deadline 轮询等 refund, 到手后短暂宽限观察 settle 缺席.
        boolean refunded = false;
        boolean settled = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            var req = backendServer.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (req == null) {
                continue;
            }
            if (req.getPath().contains("refund")) {
                refunded = true;
            }
            if (req.getPath().contains("settle")) {
                settled = true;
            }
            if (refunded && backendServer.getRequestCount() >= 5) {
                break;
            }
        }
        assertThat(refunded).as("软失败应走退款路径").isTrue();
        assertThat(settled).as("软失败不得结算").isFalse();
    }

    @Test
    @DisplayName("上游 401 → RelayException(401) 真实状态码 (不再恒 502, issue #1 缺口3)")
    void upstream401PassesThroughRealStatus() throws Exception {
        // 本用例专测真实状态码透传, 关闭轮换保持单渠道语义
        failoverProps.setEnabled(false);
        mockTokenOk();
        mockDistribute("openai");
        upstreamServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "x")));

        StepVerifier.create((Mono<?>) controller.complete("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 401
                        && re.getMessage().contains("HTTP_401"));

        waitForHealthCall();
        assertThat(healthCalls).containsExactly("failure:c1:HTTP_401");
    }

    /** fire-and-forget 健康上报异步完成, 轮询等待 (上限 5s). */
    private void waitForHealthCall() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (healthCalls.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("moderation BLOCK → 400 RelayException + 不调上游")
    void moderationBlockReturns400() {
        mockTokenOk();
        mockDistribute("openai");
        moderationApi.setOutcome(ModerationOutcome.block(List.of("sensitive_word")));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "帮我做题")));

        StepVerifier.create((Mono<?>) controller.complete("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 400
                        && ((RelayException) e).getMessage().contains("sensitive_word"));
    }

    @Test
    @DisplayName("moderation MASK → 替换最后 user message content 转发上游")
    void moderationMaskReplacesUserMessage() {
        mockTokenOk();
        mockDistribute("openai");
        moderationApi.setOutcome(ModerationOutcome.mask("<脱敏>"));
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "电话 13800138003")));

        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .assertNext(entity -> assertThat(entity.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();

        try {
            var recorded = upstreamServer.takeRequest();
            String upstreamBody = recorded.getBody().readUtf8();
            assertThat(upstreamBody).contains("<脱敏>").doesNotContain("13800138003");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("流式: 末帧 usage 真实计费 (settle 带真实 tokens) + stream_options 注入")
    void streamUsageSettledFromFrame() throws Exception {
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"" + upstreamServer.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"openai\",\"billingMode\":\"PLATFORM\"}}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":true,\"preConsumeId\":\"pc-1\"}}"));
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,"
                        + "\"completion_tokens\":7}}\n\n"
                        + "data: [DONE]\n\n"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"code\":0}"));

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("stream", true);
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef = new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    entityRef.set(entity);
                })
                .verifyComplete();

        @SuppressWarnings("unchecked")
        Flux<org.springframework.http.codec.ServerSentEvent<String>> flux =
                (Flux<org.springframework.http.codec.ServerSentEvent<String>>) entityRef.get().getBody();
        var events = flux.filter(e -> e.data() != null && !e.data().isBlank())
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
        assertThat(events).hasSize(3);

        String upstreamReq = upstreamServer.takeRequest().getBody().readUtf8();
        assertThat(upstreamReq).contains("\"include_usage\":true");

        String settleBody = null;
        for (int i = 0; i < 6 && settleBody == null; i++) {
            var recorded = backendServer.takeRequest(3, java.util.concurrent.TimeUnit.SECONDS);
            if (recorded == null) {
                break;
            }
            if (recorded.getPath().contains("/billing/settle")) {
                settleBody = recorded.getBody().readUtf8();
            }
        }
        assertThat(settleBody).isNotNull();
        assertThat(settleBody).contains("\"actualPromptTokens\":12");
        assertThat(settleBody).contains("\"actualCompletionTokens\":7");
    }

    private MockResponse jsonOk() {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0}");
    }

    /**
     * 路由链 RPC 按真实调用顺序入队: distribute → preConsume (moderation 走 MockApi 无 RPC).
     * 轮换轮 (failover) 同构.
     */
    private void mockRoute(String channelId, String preConsumeId) {
        backendServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"" + channelId + "\","
                        + "\"baseUrl\":\"" + upstreamServer.url("/").toString().replaceAll("/$", "") + "\","
                        + "\"apiKey\":\"sk-up\",\"protocol\":\"openai\",\"billingMode\":\"PLATFORM\"}}"));
        backendServer.enqueue(new MockResponse()
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
            while ((req = backendServer.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)) != null) {
                paths.add(req.getPath());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return paths;
    }

    @Test
    @DisplayName("非流式轮换: 渠道1 500 → recordFailure + refund → 渠道2 成功 (excludeChannelIds 排除)")
    void nonStreamFailoverSucceeds() throws Exception {
        mockTokenOk();
        mockRoute("c1", "pc-1");
        upstreamServer.enqueue(new MockResponse().setResponseCode(500));
        backendServer.enqueue(jsonOk());                            // refund pc-1 (failover 内)
        mockRoute("c2", "pc-2");
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"chatcmpl-failover\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));
        backendServer.enqueue(jsonOk());                            // record-failure c1 (换道成功后补记)
        backendServer.enqueue(jsonOk());                            // settle pc-2 (fire-and-forget)
        backendServer.enqueue(jsonOk());                            // access-log (fire-and-forget)

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "x")));

        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) entity.getBody();
                    assertThat(r.get("object")).isEqualTo("chat.completion");
                })
                .verifyComplete();

        assertThat(upstreamServer.getRequestCount()).isEqualTo(2);
        assertThat(backendPaths()).anyMatch(p -> p != null && p.contains("record-failure"));
        // 终态成功: 健康上报记 active 渠道成功, 不再残留渠道1 失败双计
        assertThat(healthCalls).contains("success:c2");
    }

    @Test
    @DisplayName("流式轮换: 渠道1 未吐帧 500 → recordFailure + 渠道2 SSE 成功")
    void streamFailoverSucceeds() {
        mockTokenOk();
        mockRoute("c1", "pc-1");
        upstreamServer.enqueue(new MockResponse().setResponseCode(500));
        backendServer.enqueue(jsonOk());                            // refund pc-1 (failover 内)
        mockRoute("c2", "pc-2");
        upstreamServer.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\n"
                        + "data: [DONE]\n\n"));
        backendServer.enqueue(jsonOk());                            // record-failure c1 (换道成功后补记)
        backendServer.enqueue(jsonOk());                            // settle pc-2 (fire-and-forget)
        backendServer.enqueue(jsonOk());                            // access-log (fire-and-forget)

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("stream", true);
        body.put("messages", List.of(Map.of("role", "user", "content", "hi")));

        java.util.concurrent.atomic.AtomicReference<
                org.springframework.http.ResponseEntity<Object>> entityRef = new java.util.concurrent.atomic.AtomicReference<>();
        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .assertNext(entity -> {
                    assertThat(entity.getStatusCode().value()).isEqualTo(200);
                    entityRef.set(entity);
                })
                .verifyComplete();

        @SuppressWarnings("unchecked")
        Flux<org.springframework.http.codec.ServerSentEvent<String>> flux =
                (Flux<org.springframework.http.codec.ServerSentEvent<String>>) entityRef.get().getBody();
        var events = flux.filter(e -> e.data() != null && !e.data().isBlank())
                .collectList()
                .block(java.time.Duration.ofSeconds(10));
        assertThat(events).anyMatch(e -> e.data().contains("hi"));
        assertThat(upstreamServer.getRequestCount()).isEqualTo(2);
        assertThat(backendPaths()).anyMatch(p -> p != null && p.contains("record-failure"));
    }

    @Test
    @DisplayName("轮换无候选 (单渠道): 500 可重试但换道中止 → 不中间上报, 终态 reportError 恰计 1 次 (防双计)")
    void nonStreamFailoverNoCandidateRecordsSingleFailure() throws Exception {
        mockTokenOk();
        mockRoute("c1", "pc-1");
        upstreamServer.enqueue(new MockResponse().setResponseCode(500));
        backendServer.enqueue(jsonOk());                            // refund pc-1 (failover 内)
        backendServer.enqueue(new MockResponse()                    // distribute: 无可用渠道 (业务失败信封)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"无可用渠道\",\"data\":null}"));
        backendServer.enqueue(jsonOk());                            // 终态 refund pc-1 (幂等)
        backendServer.enqueue(jsonOk());                            // access-log (fire-and-forget)

        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-4o");
        body.put("messages", List.of(Map.of("role", "user", "content", "x")));

        StepVerifier.create(controller.complete("Bearer sk-test", null, body))
                .verifyErrorMatches(e -> e instanceof RelayException re && re.getHttpStatus() == 500);

        waitForHealthCall();
        assertThat(upstreamServer.getRequestCount()).isEqualTo(1);
        // 轮换中止时不再中间上报 record-failure, 该失败只由终态 reportError 计一次
        assertThat(backendPaths()).noneMatch(p -> p != null && p.contains("record-failure"));
        assertThat(healthCalls.stream().filter(c -> c.startsWith("failure:c1")).count()).isEqualTo(1L);
    }

    /**
     * 桩 HttpModerationApi, 不发 HTTP, 直接返回预设 ModerationOutcome.
     * 继承只是为了满足 ModerationGate 构造器类型; scan() 完全覆盖父类实现.
     */
    private static class MockModerationApi extends fun.commons.tokengateway.rpc.HttpModerationApi {

        private ModerationOutcome outcome = ModerationOutcome.pass(null);

        MockModerationApi() {
            super(WebClient.builder(), new fun.commons.tokengateway.config.GatewayProperties(),
                    new RpcInternalAuth(new fun.commons.tokengateway.config.GatewayProperties()));
        }

        void setOutcome(ModerationOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Mono<ModerationOutcome> scan(fun.commons.tokengateway.moderation.ScanRequest request) {
            return Mono.just(outcome);
        }

        // 输出审查默认通过 (Controller 非流式 audit 链路, 不发 HTTP)
        @Override
        public Mono<fun.commons.tokengateway.framework.ApiResponse<fun.commons.tokengateway.contract.ModerationAuditVO>> audit(
                fun.commons.tokengateway.contract.ModerationAuditRequest request) {
            return Mono.just(fun.commons.tokengateway.framework.ApiResponse.success(
                    fun.commons.tokengateway.contract.ModerationAuditVO.builder()
                            .passed(true).actionTaken("LOG").source("NONE").build()));
        }
    }
}
