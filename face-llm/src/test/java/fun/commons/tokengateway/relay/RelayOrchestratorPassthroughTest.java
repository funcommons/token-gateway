package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.config.ErrorContractProperties;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.rpc.AdapterSelector;
import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpModerationApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpCutover;
import fun.commons.tokengateway.thmp.ThmpShadow;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayOrchestrator 白名单透传单测 (issue #24): distribute/preConsume 失败分支的能力面
 * 原码命中 gateway.error-passthrough-codes → 原码 + 语义 HTTP 状态透传;
 * 未命中维持既有 502+10004 降级不变.
 */
@DisplayName("RelayOrchestrator 白名单透传 (issue #24)")
class RelayOrchestratorPassthroughTest {

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
                new HttpTokenApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new HttpChannelApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new HttpBillingApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new ModerationGate(new HttpModerationApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props), moderationOnSpi())),
                new ThmpShadow.Noop(),
                new ThmpCutover.Noop());
    }

    /**
     * issue #38: moderation.enabled 缺省 false 起 HttpModerationApi 双闸短路 (不发 RPC),
     * 本类用例 enqueue 了 scan 响应期待 RPC 真实下发, 夹具显式开启.
     */
    private static TokenGatewayProperties moderationOnSpi() {
        var spi = new TokenGatewayProperties();
        spi.getModeration().setEnabled(true);
        return spi;
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    private void mockTokenOk() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
    }

    private void mockDistribute(int code, String message) {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":" + code + ",\"message\":\"" + message + "\"}"));
    }

    @Test
    @DisplayName("distribute 业务码 4090 (风控) → 403 + 原码 4090 + 原始 message")
    void distribute4090Passes403() {
        mockTokenOk();
        mockDistribute(4090, "risk rejected");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 403
                        && re.getCode() == 4090
                        && "risk rejected".equals(re.getMessage()));
    }

    @Test
    @DisplayName("distribute 业务码 10602 → 404 + 原码 10602")
    void distribute10602Passes404() {
        mockTokenOk();
        mockDistribute(10602, "model not found");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404
                        && re.getCode() == 10602);
    }

    @Test
    @DisplayName("distribute 非白名单原码 20199 → 仍 502 + 10004 (原码仅 message 残存)")
    void distributeUnknownCodeStill502() {
        mockTokenOk();
        mockDistribute(20199, "weird failure");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502
                        && re.getCode() == 10004
                        && re.getMessage().contains("channel distribute failed")
                        && re.getMessage().contains("weird failure"));
    }

    @Test
    @DisplayName("10400 不在默认白名单 → 既有 404 + 10400 映射不变 (白名单优先但不误伤)")
    void distribute10400Unchanged() {
        mockTokenOk();
        mockDistribute(10400, "no channel");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404
                        && re.getCode() == 10400);
    }

    @Test
    @DisplayName("preConsume 业务码 10601 (余额不足) → 402 + 原码 10601")
    void preConsume10601Passes402() {
        mockTokenOk();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\","
                        + "\"baseUrl\":\"http://u\",\"apiKey\":\"sk\",\"protocol\":\"openai\","
                        + "\"ownerType\":\"TENANT\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"actionTaken\":\"PASS\",\"sanitizedContent\":null}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10601,\"message\":\"余额不足\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402
                        && re.getCode() == 10601
                        && "余额不足".equals(re.getMessage()));
    }

    @Test
    @DisplayName("白名单可配置: 收窄后 4090 不再透传 → 502 + 10004 (十参构造注入)")
    void whitelistConfigurable() throws Exception {
        backend.shutdown();
        backend = new MockWebServer();
        backend.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        var contractProps = new ErrorContractProperties();
        // 收窄: 清空默认白名单 (配置按键合并不可删除默认键, 代码装配/以宽值覆盖可收窄语义)
        contractProps.setErrorPassthroughCodes(new HashMap<>());
        WebClient.Builder b = WebClient.builder();
        RelayOrchestrator custom = new RelayOrchestrator(
                new HttpTokenApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new HttpChannelApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new HttpBillingApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props)),
                new ModerationGate(new HttpModerationApi(b, new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props), moderationOnSpi())),
                new ThmpShadow.Noop(),
                new ThmpCutover.Noop(),
                new AdapterSelector(new TokenGatewayProperties()),
                null, null, contractProps);

        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":4090,\"message\":\"risk rejected\"}"));

        StepVerifier.create(custom.prepare("sk", "gpt-4o", 0, 0, null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502
                        && re.getCode() == 10004);
    }
}
