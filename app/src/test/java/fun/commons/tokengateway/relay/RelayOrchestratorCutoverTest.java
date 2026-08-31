package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.thmp.ThmpCandidateCache;
import fun.commons.tokengateway.thmp.ThmpContractClient;
import fun.commons.tokengateway.thmp.ThmpContractProperties;
import fun.commons.tokengateway.thmp.ThmpCutoverRouter;
import fun.commons.tokengateway.thmp.ThmpKeyCipher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RelayOrchestrator 切流链路测试 (S2-W3 前置): 双 MockWebServer (旧 backend + 假 THMP 契约面).
 *
 * <p>断言三件事: ① 切流命中 → 旧 backend 收不到 distribute, 路由 = THMP 候选 + 明文 key 解出;
 * ② THMP resolve 失败 → 自动回旧 distribute (一键回旧语义); ③ Noop → 旧链路直走.
 */
@DisplayName("RelayOrchestrator 切流 (W3 灰度)")
class RelayOrchestratorCutoverTest {

    private static final String PASS = "thmp-dev-enc-passphrase-0000000000000001";

    private MockWebServer backend;
    private MockWebServer thmp;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        thmp = new MockWebServer();
        thmp.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
        thmp.shutdown();
    }

    private RelayOrchestrator orchestrator(ThmpContractProperties props) {
        var b = WebClient.builder();
        var gwProps = new fun.commons.tokengateway.config.GatewayProperties();
        gwProps.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        var tokenApi = new fun.commons.tokengateway.rpc.HttpTokenApi(b, gwProps,
                new fun.commons.tokengateway.rpc.RpcInternalAuth(gwProps));
        var channelApi = new fun.commons.tokengateway.rpc.HttpChannelApi(b, gwProps,
                new fun.commons.tokengateway.rpc.RpcInternalAuth(gwProps));
        var billingApi = new fun.commons.tokengateway.rpc.HttpBillingApi(b, gwProps,
                new fun.commons.tokengateway.rpc.RpcInternalAuth(gwProps));
        var moderationGate = new fun.commons.tokengateway.moderation.ModerationGate(
                new fun.commons.tokengateway.rpc.HttpModerationApi(b, gwProps,
                        new fun.commons.tokengateway.rpc.RpcInternalAuth(gwProps)));
        ThmpContractClient client = new ThmpContractClient(b, props);
        ThmpCandidateCache cache = new ThmpCandidateCache(client, Duration.ofSeconds(30));
        ThmpCutoverRouter cutover = new ThmpCutoverRouter(props, cache,
                new ThmpKeyCipher(java.util.Map.of(), PASS));
        return new RelayOrchestrator(tokenApi, channelApi, billingApi, moderationGate,
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(), cutover);
    }

    private ThmpContractProperties thmpProps() {
        ThmpContractProperties props = new ThmpContractProperties();
        props.setEnabled(true);
        props.setBaseUrl(thmp.url("/").toString().replaceAll("/$", ""));
        props.setClientId("cid-x");
        props.setSecret("secret-0000000000000000000000000001");
        props.setTimeout(Duration.ofSeconds(2));
        props.setCutoverModels(List.of("gpt-4o"));
        props.setCutoverPercent(100);
        return props;
    }

    private void enqueueTokenValidate() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\","
                        + "\"userId\":\"2\",\"tenantId\":\"3\"}}"));
    }

    private void enqueueModerationAndPreConsume() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"actionTaken\":\"PASS\",\"sanitizedContent\":null}}"));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pre-1\",\"estimatedQuota\":0,\"success\":true}}"));
    }

    @Test
    @DisplayName("切流命中: 旧 backend 无 distribute 调用 + 路由 = THMP 候选 (明文 key 解出)")
    void cutoverRoutesViaThmp() throws Exception {
        enqueueTokenValidate();
        enqueueModerationAndPreConsume();
        // 假 THMP: resolve 返回候选 (密文 key 用同口令加密)
        ThmpKeyCipher cipher = new ThmpKeyCipher(java.util.Map.of(), PASS);
        thmp.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"candidates\":[{\"procurement_model_id\":\"11\","
                        + "\"channel_id\":\"55\",\"priority\":1,\"upstream_base_url\":\"https://thmp-up.io/v1\","
                        + "\"protocol_type\":\"openai\",\"key_select_mode\":\"ROUND_ROBIN\","
                        + "\"keys\":[{\"key_id\":\"7\",\"key_cipher_tenant\":\""
                        + cipher.encrypt(0, "sk-thmp-decrypted") + "\"}],"
                        + "\"model_params\":null,\"capacity\":null,\"cost\":null}],"
                        + "\"blocked\":[],\"affinity_hit\":false,\"cache_hit\":false},"
                        + "\"error\":null,\"trace_id\":\"t\",\"timestamp\":1}"));

        StepVerifier.create(orchestrator(thmpProps()).prepare("sk", "gpt-4o", 0, 0, null, "req-1"))
                .assertNext(p -> {
                    assertThat(p.channel().getChannelId()).isEqualTo("55");
                    assertThat(p.channel().getBaseUrl()).isEqualTo("https://thmp-up.io/v1");
                    assertThat(p.channel().getApiKey()).isEqualTo("sk-thmp-decrypted");
                })
                .verifyComplete();

        // 旧 backend: 只收 token validate + moderation + preConsume = 3 次, 无 distribute
        assertThat(backend.getRequestCount()).isEqualTo(3);
        assertThat(thmp.getRequestCount()).isEqualTo(1);
        assertThat(thmp.takeRequest().getPath()).isEqualTo("/v1/candidates/resolve");
    }

    @Test
    @DisplayName("THMP resolve 失败 → 自动回旧 distribute (不炸主链)")
    void cutoverFallsBackOnThmpFailure() throws Exception {
        enqueueTokenValidate();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\",\"baseUrl\":\"http://old\","
                        + "\"apiKey\":\"sk-old\",\"protocol\":\"openai\",\"ownerType\":\"PLATFORM\"}}"));
        enqueueModerationAndPreConsume();
        thmp.enqueue(new MockResponse().setResponseCode(500).setBody("thmp down"));

        StepVerifier.create(orchestrator(thmpProps()).prepare("sk", "gpt-4o", 0, 0, null, "req-1"))
                .assertNext(p -> {
                    assertThat(p.channel().getChannelId()).isEqualTo("c1");
                    assertThat(p.channel().getApiKey()).isEqualTo("sk-old");
                })
                .verifyComplete();
        assertThat(thmp.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("名单外模型 → 旧链路直走 (不触 THMP)")
    void nonListedModelGoesOldPath() throws Exception {
        enqueueTokenValidate();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"c1\",\"baseUrl\":\"http://old\","
                        + "\"apiKey\":\"sk-old\",\"protocol\":\"openai\",\"ownerType\":\"PLATFORM\"}}"));
        enqueueModerationAndPreConsume();

        StepVerifier.create(orchestrator(thmpProps()).prepare("sk", "claude-x", 0, 0, null, "req-1"))
                .assertNext(p -> assertThat(p.channel().getChannelId()).isEqualTo("c1"))
                .verifyComplete();
        assertThat(thmp.getRequestCount()).isEqualTo(0);
    }
}
