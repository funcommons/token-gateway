package fun.commons.tokengateway.relay;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.moderation.ModerationGate;
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
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayOrchestrator distribute clientIp 接线单测 (issue #37): prepare 七参 clientIp
 * 随 DistributeRequest 下发 (null = 字段缺席/为 null, 序列化不炸), 并驻留 PreparedRequest
 * 供请求内 failover 重分发保持同值; 默认白名单扩员 10612→403 (IP 白名单拒绝, 与 4090 同类)
 * 的透传分支判定 (写法参照 RelayOrchestratorErrorPassthroughTest 4090 用例).
 */
@DisplayName("RelayOrchestrator distribute clientIp + 10612 白名单 (issue #37)")
class RelayOrchestratorDistributeClientIpTest {

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
     * 本类 enqueueModerationPass 期待 scan RPC 真实下发 (takeRequest 按序断言), 夹具显式开启.
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

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private void enqueueValidateOk() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                + "\"userId\":\"2\",\"tenantId\":\"3\",\"groupId\":\"g1\"}}"));
    }

    private void enqueueDistributeOk(String channelId) {
        backend.enqueue(json("{\"code\":0,\"data\":{\"channelId\":\"" + channelId + "\","
                + "\"baseUrl\":\"http://u\",\"apiKey\":\"sk\",\"protocol\":\"openai\","
                + "\"ownerType\":\"PLATFORM\"}}"));
    }

    private void enqueueModerationPass() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"actionTaken\":\"PASS\",\"sanitizedContent\":null}}"));
    }

    private void enqueuePreConsumeOk(String preConsumeId) {
        backend.enqueue(json("{\"code\":0,\"data\":{\"preConsumeId\":\"" + preConsumeId
                + "\",\"success\":true}}"));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest req = backend.takeRequest(5, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        return req;
    }

    @Test
    @DisplayName("prepare 带 clientIp → distribute 请求体携带 clientIp")
    void prepareDistributeCarriesClientIp() throws InterruptedException {
        enqueueValidateOk();
        enqueueDistributeOk("c1");
        enqueueModerationPass();
        enqueuePreConsumeOk("pc1");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 10, 20, null, "req-1", "7.7.7.7"))
                .assertNext(prepared -> assertThat(prepared.clientIp()).isEqualTo("7.7.7.7"))
                .verifyComplete();

        takeRequest(); // validate
        RecordedRequest distribute = takeRequest();
        assertThat(distribute.getPath()).contains("/api/v1/internal/channels/distribute");
        JSONObject body = JSON.parseObject(distribute.getBody().readUtf8());
        assertThat(body.getString("clientIp")).isEqualTo("7.7.7.7");
    }

    @Test
    @DisplayName("prepare 不带 clientIp → distribute 请求体 clientIp 为 null/缺席 (序列化不炸, 旧语义)")
    void prepareWithoutClientIpDistributeFieldAbsent() throws InterruptedException {
        enqueueValidateOk();
        enqueueDistributeOk("c1");
        enqueueModerationPass();
        enqueuePreConsumeOk("pc1");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .assertNext(prepared -> assertThat(prepared.clientIp()).isNull())
                .verifyComplete();

        takeRequest(); // validate
        RecordedRequest distribute = takeRequest();
        JSONObject body = JSON.parseObject(distribute.getBody().readUtf8());
        assertThat(body.get("clientIp")).isNull();
    }

    @Test
    @DisplayName("failover 重分发: 第二次 distribute 同值携带 clientIp (PreparedRequest 驻留透传)")
    void failoverSecondDistributeCarriesClientIp() throws InterruptedException {
        enqueueValidateOk();
        enqueueDistributeOk("c1");
        enqueueModerationPass();
        enqueuePreConsumeOk("pc1");
        // failover 链: refund → distribute#2 → preConsume#2
        backend.enqueue(json("{\"code\":0}"));
        enqueueDistributeOk("c2");
        enqueuePreConsumeOk("pc2");

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 10, 20, null, "req-1", "7.7.7.7")
                        .flatMap(prepared -> orchestrator.failoverToNextChannel(prepared,
                                "gpt-4o", 10, 20, List.of("c1"))))
                .assertNext(next -> {
                    assertThat(next.channel().getChannelId()).isEqualTo("c2");
                    assertThat(next.clientIp()).isEqualTo("7.7.7.7");
                })
                .verifyComplete();

        takeRequest(); // validate
        RecordedRequest firstDistribute = takeRequest();
        takeRequest(); // moderation
        takeRequest(); // preConsume#1
        takeRequest(); // refund
        RecordedRequest secondDistribute = takeRequest();

        assertThat(JSON.parseObject(firstDistribute.getBody().readUtf8()).getString("clientIp"))
                .isEqualTo("7.7.7.7");
        JSONObject second = JSON.parseObject(secondDistribute.getBody().readUtf8());
        assertThat(second.getString("clientIp")).isEqualTo("7.7.7.7");
        assertThat(second.getJSONArray("excludeChannelIds")).containsExactly("c1");
    }

    @Test
    @DisplayName("distribute code=10612 (IP 白名单拒绝, issue #37 入默认白名单) → HTTP 403 + 原码 10612 + 原始 message")
    void distribute10612Passes403() {
        enqueueValidateOk();
        backend.enqueue(json("{\"code\":10612,\"message\":\"客户端 IP 不在白名单\"}"));

        StepVerifier.create(orchestrator.prepare("sk", "gpt-4o", 0, 0, null, null, "1.2.3.4"))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 403
                        && re.getCode() == 10612
                        && "客户端 IP 不在白名单".equals(re.getMessage()));
    }
}
