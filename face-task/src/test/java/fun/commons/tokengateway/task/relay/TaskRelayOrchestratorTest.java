package fun.commons.tokengateway.task.relay;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.idempotency.IdempotencyStore;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.task.ResourceUrlConverter;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.billing.TaskBillingSaga;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.lotask.RouteSnapshotCipher;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskNoMappingStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskRelayOrchestrator 单测 (《06》M2.5a 出口: create→poll 走通 + 两条负路径).
 *
 * <p>控制层 (token/distribute/billing) 用 MockWebServer 真 RPC;
 * lotask4j 与映射存储 Mockito 隔离.
 */
@DisplayName("TaskRelayOrchestrator")
class TaskRelayOrchestratorTest {

    private MockWebServer backend;
    private TaskRelayOrchestrator orchestrator;
    private LotaskTaskClient lotaskClient;
    private TaskNoMappingStore mappingStore;
    private TaskMetaStore metaStore;
    private RouteSnapshotCipher cipher;

    private static final String CIPHER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        GatewayProperties gwProps = new GatewayProperties();
        gwProps.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        WebClient.Builder b = WebClient.builder();
        RpcInternalAuth auth = new RpcInternalAuth(gwProps);

        IdempotencyStore alwaysFirst = new IdempotencyStore() {
            @Override
            public Mono<Boolean> tryAcquire(String key, Duration ttl) {
                return Mono.just(true);
            }

            @Override
            public Mono<Void> release(String key) {
                return Mono.empty();
            }
        };

        lotaskClient = mock(LotaskTaskClient.class);
        mappingStore = mock(TaskNoMappingStore.class);
        cipher = new RouteSnapshotCipher(CIPHER_KEY);

        TaskMetaStore metaStore = mock(TaskMetaStore.class);
        when(metaStore.onCreated(anyString(), any(), any())).thenReturn(Mono.empty());
        when(metaStore.getTerminalResult(anyString())).thenReturn(Mono.empty());

        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().getLotask().setWebhookCallbackUrl("http://gw/internal/lotask/webhook");
        props.getTask().setResourceSignKey("test-sign-key");

        orchestrator = new TaskRelayOrchestrator(
                new HttpTokenApi(b, gwProps, auth),
                new HttpChannelApi(b, gwProps, auth),
                new TaskBillingSaga(new HttpBillingApi(b, gwProps, auth), alwaysFirst),
                lotaskClient, cipher, mappingStore, metaStore,
                new ResourceUrlConverter(new ResourceSigner(props)), props);
        this.metaStore = metaStore;
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    private static void enqueueHappyControlPlane(MockWebServer backend) {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"t1\","
                + "\"userId\":\"u1\",\"tenantId\":\"tn1\"}}"));
        backend.enqueue(json("{\"code\":0,\"data\":{\"channelId\":\"ch1\",\"baseUrl\":\"https://up\","
                + "\"apiKey\":\"sk-upstream\",\"ownerType\":\"PLATFORM\"}}"));
        backend.enqueue(json("{\"code\":0,\"data\":{\"preConsumeId\":\"pc1\",\"success\":true}}"));
    }

    @Test
    @DisplayName("create 正常路径: 控制层三步 → lotask submit (幂等键=task_no, 快照加密) → PENDING 返回")
    void createHappyPath() {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), eq("http://gw/internal/lotask/webhook")))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("video", "sk-caller",
                        Map.of("model", "kling-v1", "params", Map.of("seconds", 5)), "trace-1"))
                .assertNext(view -> {
                    String taskNo = (String) view.get("task_no");
                    assertThat(taskNo).startsWith("T");
                    assertThat(view.get("status")).isEqualTo("PENDING");
                    assertThat(view.get("poll_url")).isEqualTo("/v1/videos/" + taskNo);
                })
                .verifyComplete();

        // submit 载荷: 幂等键 = task_no; 路由快照密文可解密且含出站凭证
        ArgumentCaptor<String> idemKey = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lotaskClient).submit(eq("video"), idemKey.capture(), payload.capture(), anyString());
        assertThat(idemKey.getValue()).startsWith("T");
        String snapshot = (String) payload.getValue().get("routeSnapshot");
        assertThat(snapshot).doesNotContain("sk-upstream");
        JSONObject decrypted = JSON.parseObject(cipher.decrypt(snapshot));
        assertThat(decrypted.getString("baseUrl")).isEqualTo("https://up");
        assertThat(decrypted.getString("apiKey")).isEqualTo("sk-upstream");
    }

    @Test
    @DisplayName("余额不足 → 402 + 10617, 不产生任务 (lotask submit 不调用)")
    void createInsufficientBalance() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"t1\","
                + "\"userId\":\"u1\",\"tenantId\":\"tn1\"}}"));
        backend.enqueue(json("{\"code\":0,\"data\":{\"channelId\":\"ch1\",\"baseUrl\":\"https://up\","
                + "\"apiKey\":\"sk-upstream\",\"ownerType\":\"PLATFORM\"}}"));
        backend.enqueue(json("{\"code\":10617,\"message\":\"余额不足\",\"data\":null}"));

        StepVerifier.create(orchestrator.create("video", "sk-caller", Map.of("model", "kling-v1"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402
                        && re.getCode() == ApiCode.INSUFFICIENT_BALANCE.getCode())
                .verify();
        verify(lotaskClient, never()).submit(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("submit 失败 → 全额退款 (refund RPC 发出) + 502 + 10004")
    void createSubmitFailRefunds() {
        enqueueHappyControlPlane(backend);
        backend.enqueue(json("{\"code\":0}")); // refund
        when(lotaskClient.submit(anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                        "lotask submit RPC 失败: boom")));

        StepVerifier.create(orchestrator.create("image", "sk-caller", Map.of("model", "sd-xl"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502
                        && re.getCode() == ApiCode.THIRD_PARTY_ERROR.getCode())
                .verify();
        // 第 4 个请求 = refund
        try {
            backend.takeRequest(); // validate
            backend.takeRequest(); // distribute
            backend.takeRequest(); // pre-consume
            okhttp3.mockwebserver.RecordedRequest refund = backend.takeRequest();
            assertThat(refund.getPath()).contains("/api/v1/internal/billing/refund");
            assertThat(refund.getBody().readUtf8()).contains("pc1");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("poll: 映射缺失 → 404 + 10400")
    void pollMappingMissing() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-ghost")).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.poll("video", "T-ghost", "sk-caller"))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404
                        && re.getCode() == ApiCode.NOT_FOUND.getCode())
                .verify();
    }

    @Test
    @DisplayName("poll: SUCCEEDED → 状态映射 + result 资源转代理 URL (永不透传)")
    void pollSucceeded() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-ok")).thenReturn(Mono.just("YeirYkxHuQ"));
        when(lotaskClient.get("YeirYkxHuQ")).thenReturn(Mono.just(new LotaskTaskView(
                "YeirYkxHuQ", "SUCCESS",
                Map.of("resources", List.of("https://up/v.mp4"), "usage", Map.of("seconds", 5)),
                null, null)));

        StepVerifier.create(orchestrator.poll("video", "T-ok", "sk-caller"))
                .assertNext(view -> {
                    assertThat(view.get("task_no")).isEqualTo("T-ok");
                    assertThat(view.get("status")).isEqualTo("SUCCEEDED");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) view.get("result");
                    assertThat((List<?>) result.get("resources")).allSatisfy(u ->
                            assertThat((String) u).startsWith("/v1/resources/T-ok/0?"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("poll: FAILED → error 块返回")
    void pollFailed() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-bad")).thenReturn(Mono.just("YeirYkxHuQ"));
        when(lotaskClient.get("YeirYkxHuQ")).thenReturn(Mono.just(new LotaskTaskView(
                "YeirYkxHuQ", "FAILED", null, "UPSTREAM_ERROR", "上游超时")));

        StepVerifier.create(orchestrator.poll("video", "T-bad", "sk-caller"))
                .assertNext(view -> {
                    assertThat(view.get("status")).isEqualTo("FAILED");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> error = (Map<String, Object>) view.get("error");
                    assertThat(error.get("code")).isEqualTo("UPSTREAM_ERROR");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("poll: 终态条目存在 → 幂等返回 (不触 lotask, resources 已是代理 URL)")
    void pollTerminalEntry() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-done")).thenReturn(Mono.just("YeirYkxHuQ"));
        when(metaStore.getTerminalResult("T-done")).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"SUCCEEDED\","
                        + "\"result\":{\"resources\":[\"/v1/resources/T-done/0?exp=1&sig=x\"]}}")));

        StepVerifier.create(orchestrator.poll("video", "T-done", "sk-caller"))
                .assertNext(view -> {
                    assertThat(view.get("status")).isEqualTo("SUCCEEDED");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) view.get("result");
                    assertThat(result.get("resources").toString()).contains("/v1/resources/T-done/0");
                })
                .verifyComplete();
        verify(lotaskClient, never()).get(anyString());
    }
}
