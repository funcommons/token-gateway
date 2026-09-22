package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.exception.RelayException;
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
                new HttpTokenApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new HttpChannelApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new fun.commons.tokengateway.rpc.HttpBillingApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new ModerationGate(new fun.commons.tokengateway.rpc.HttpModerationApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props), new RpcInternalAuth(props), moderationOnSpi())),
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

        StepVerifier.create(orchestrator.prepare("sk-test", "gpt-4o", 0, 0, null, null, null))
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
        StepVerifier.create(orchestrator.prepare(null, "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("token 无效 → 401")
    void tokenInvalid() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":false}}"));

        StepVerifier.create(orchestrator.prepare("sk-bad", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("token 校验 RPC 失败 → 504 + 10003 (issue #22: 区别于 token 无效 401)")
    void tokenRpcFailure() {
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(orchestrator.prepare("sk-test", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 504
                        && re.getCode() == 10003);
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

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 404
                        && ((RelayException) e).getCode() == 10400
                        && e.getMessage().contains("no channel"));
    }

    @Test
    @DisplayName("channel distribute 业务码 20103 (bootstrap MODEL_NOT_FOUND) → 404 + 信封 10400 (P1-5)")
    void distributeModelNotFound20103() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\",\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":20103,\"message\":\"模型不存在或已下线: no-such-model\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "no-such-model", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 404
                        && ((RelayException) e).getCode() == 10400
                        && e.getMessage().contains("no-such-model"));
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

        StepVerifier.create(orchestrator.prepare("sk", "claude", 0, 0, null, null, null))
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

        StepVerifier.create(orchestrator.prepare("sk", "claude-haiku-4-5", 0, 0, null, null, null))
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

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
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

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
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

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 402
                        && ((RelayException) e).getCode() == 10617
                        && e.getMessage().contains("用户算力余额不足"));
    }

    /**
     * issue #38: moderation.enabled 缺省 false 起 HttpModerationApi 双闸短路 (不发 RPC),
     * 本类用例 enqueue 了 scan 响应期待 RPC 真实下发, 夹具显式开启.
     */
    private static fun.commons.tokengateway.spi.config.TokenGatewayProperties moderationOnSpi() {
        var spi = new fun.commons.tokengateway.spi.config.TokenGatewayProperties();
        spi.getModeration().setEnabled(true);
        return spi;
    }

    @Test
    @DisplayName("issue #38 enabled=false: prepare 级 scan 短路零 RPC, 管线正常通过 (validate→distribute→preConsume)")
    void disabledModerationSkipsScanRpcAtPrepareLevel() throws Exception {
        // 独立装配 enabled=false (缺省形态) 的 orchestrator; 不 enqueue 任何 scan 响应 —
        // 若闸失效, scan RPC 会消费到 preConsume 的响应导致链错位/失败
        backend.shutdown();
        backend = new MockWebServer();
        backend.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        WebClient.Builder b = WebClient.builder();
        var offSpi = new fun.commons.tokengateway.spi.config.TokenGatewayProperties();
        // 故意配 url 复刻「靠 bug 扫描」误配形态: enabled=false 时 url 不得被触达
        offSpi.getModeration().setUrl(props.getUrl());
        RelayOrchestrator off = new RelayOrchestrator(
                new HttpTokenApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(offSpi, props), new RpcInternalAuth(props)),
                new HttpChannelApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(offSpi, props), new RpcInternalAuth(props)),
                new fun.commons.tokengateway.rpc.HttpBillingApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(offSpi, props), new RpcInternalAuth(props)),
                new ModerationGate(new fun.commons.tokengateway.rpc.HttpModerationApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(offSpi, props), new RpcInternalAuth(props), offSpi)),
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());

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
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pre-off\",\"estimatedQuota\":0,\"success\":true}}"));

        StepVerifier.create(off.prepare("sk-test", "gpt-4o", 0, 0, "你好", null, null))
                .assertNext(p -> {
                    assertThat(p.preConsumeId()).isEqualTo("pre-off");
                    assertThat(p.moderationSanitized()).isNull();
                })
                .verifyComplete();

        // 恰好 3 次 RPC (validate/distribute/preConsume), 无任何 /moderation/scan
        assertThat(backend.getRequestCount()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            var recorded = backend.takeRequest();
            assertThat(recorded.getPath()).doesNotContain("/moderation/");
        }
    }
}
