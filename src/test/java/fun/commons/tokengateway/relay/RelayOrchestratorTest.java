package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.controller.RelayException;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayOrchestrator 单测: token 校验 + 渠道路由的 Reactor 链.
 */
@DisplayName("RelayOrchestrator")
class RelayOrchestratorTest {

    private MockWebServer backend;
    private RelayOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        WebClient.Builder b = WebClient.builder();
        orchestrator = new RelayOrchestrator(
                new HttpTokenApi(b, props, new RpcInternalAuth(props)),
                new HttpChannelApi(b, props, new RpcInternalAuth(props)),
                new fun.commons.tokengateway.rpc.HttpBillingApi(b, props, new RpcInternalAuth(props)),
                new ModerationGate(new fun.commons.tokengateway.rpc.HttpModerationApi(b, props, new RpcInternalAuth(props))),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("正常路径: token valid + channel 返回 → PreparedRequest(token, channel)")
    void happyPath() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"http://u\",\"apiKey\":\"sk\",\"protocol\":\"openai\",\"ownerType\":\"TENANT\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"actionTaken\":\"PASS\",\"sanitizedContent\":null}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pre-1\",\"estimatedQuota\":0,\"success\":true}}"));

        StepVerifier.create(orchestrator.prepare("sk-test", "gpt-4o", 0, 0, null, null))
                .assertNext(p -> {
                    assertThat(p.token().getTokenId()).isEqualTo("1");
                    assertThat(p.channel().getChannelId()).isEqualTo("c1");
                    assertThat(p.channel().getProtocol()).isEqualTo("openai");
                    assertThat(p.preConsumeId()).isEqualTo("pre-1");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("apiKey 缺失 → 401 RelayException")
    void missingApiKey() {
        StepVerifier.create(orchestrator.prepare(null, "gpt-4o", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("token 无效 → 401")
    void tokenInvalid() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":false}}"));

        StepVerifier.create(orchestrator.prepare("sk-bad", "gpt-4o", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("channel distribute 业务码 10400 → 404 + 信封 10400 (模型不存在/无可用渠道)")
    void distributeFailed() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"no channel\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 404
                        && ((RelayException) e).getCode() == 10400
                        && e.getMessage().contains("no channel"));
    }

    @Test
    @DisplayName("PASS 回显 sanitizedContent → moderationSanitized=null, body 不被改写")
    void passThroughDoesNotMask() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\",\"baseUrl\":\"http://u\","
                        + "\"apiKey\":\"sk\",\"protocol\":\"anthropic\",\"ownerType\":\"TENANT\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"actionTaken\":\"PASS\",\"sanitizedContent\":\"\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pre-2\",\"estimatedQuota\":0,\"success\":true}}"));

        StepVerifier.create(orchestrator.prepare("sk", "claude", 0, 0, null, null))
                .assertNext(p -> assertThat(p.moderationSanitized()).isNull())
                .verifyComplete();
    }

    @Test
    @DisplayName("extractUserContent: Anthropic block 数组 → 拼接 text 块")
    void extractUserContentFromBlocks() {
        Map<String, Object> body = anthropicBody("你好");
        assertThat(RelayOrchestrator.extractUserContent(body)).isEqualTo("你好");
    }

    @Test
    @DisplayName("applyMask: sanitizedContent 为空白 → body 原样返回")
    void applyMaskSkipsBlank() {
        Map<String, Object> body = anthropicBody("你好");
        assertThat(RelayOrchestrator.applyMask(body, "")).isSameAs(body);
        assertThat(RelayOrchestrator.applyMask(body, null)).isSameAs(body);
    }

    @Test
    @DisplayName("applyMask: block 数组只替换 text, 保留 cache_control")
    @SuppressWarnings("unchecked")
    void applyMaskKeepsCacheControl() {
        Map<String, Object> masked = RelayOrchestrator.applyMask(anthropicBody("我的电话是 138"), "我的电话是 <PII>");
        List<Object> messages = (List<Object>) masked.get("messages");
        Map<String, Object> last = (Map<String, Object>) messages.get(messages.size() - 1);
        Map<String, Object> block = (Map<String, Object>) ((List<Object>) last.get("content")).get(0);
        assertThat(block.get("text")).isEqualTo("我的电话是 <PII>");
        assertThat(block.get("cache_control")).isNotNull();
    }

    private static Map<String, Object> anthropicBody(String text) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "text");
        block.put("text", text);
        block.put("cache_control", Map.of("type", "ephemeral"));
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", List.of(block));
        Map<String, Object> body = new HashMap<>();
        body.put("messages", List.of(message));
        return body;
    }

    @Test
    @DisplayName("distribute 失败 → 502 message 透传主应用根因 (model 未配置可见)")
    void distributeFailMessagePropagated() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10404,\"message\":\"无可用渠道: model=claude-haiku-4-5\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "claude-haiku-4-5", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 502
                        && e.getMessage().contains("无可用渠道: model=claude-haiku-4-5"));
    }

    @Test
    @DisplayName("distribute RPC 失败 → 502 message 透传 SERVICE_TIMEOUT 原因")
    void distributeRpcFailMessageFallback() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10503,\"message\":\"rpc down\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 502
                        && e.getMessage().contains("rpc down"));
    }

    @Test
    @DisplayName("preConsume 失败 → 502 message 透传 failReason (余额不足可见)")
    void preConsumeFailReasonPropagated() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"http://u\",\"apiKey\":\"sk\",\"protocol\":\"openai\",\"ownerType\":\"TENANT\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"success\":false,\"failReason\":\"用户算力余额不足\"}}"));

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 502
                        && e.getMessage().contains("billing preConsume failed")
                        && e.getMessage().contains("用户算力余额不足"));
    }

    @Test
    @DisplayName("preConsume 信封 10617 → 402 + 信封 10617 (余额不足语义透传)")
    void preConsumeInsufficientBalance() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"http://u\",\"apiKey\":\"sk\",\"protocol\":\"openai\",\"ownerType\":\"TENANT\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"passed\":true,\"actionTaken\":\"LOG\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10617,\"message\":\"用户算力余额不足\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 402
                        && ((RelayException) e).getCode() == 10617
                        && e.getMessage().contains("用户算力余额不足"));
    }
}
