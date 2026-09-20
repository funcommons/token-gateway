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
import okhttp3.mockwebserver.RecordedRequest;
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
    private TokenGatewayProperties props;

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
        this.props = props;

        orchestrator = new TaskRelayOrchestrator(
                new HttpTokenApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), gwProps), auth),
                new HttpChannelApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), gwProps), auth),
                new fun.commons.tokengateway.rpc.AdapterSelector(new fun.commons.tokengateway.spi.config.TokenGatewayProperties()),
                new fun.commons.tokengateway.rpc.TokenRouteClient(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), gwProps), auth,
                        new fun.commons.tokengateway.spi.config.TokenGatewayProperties()),
                new TaskBillingSaga(new HttpBillingApi(b, new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), gwProps), auth), alwaysFirst),
                lotaskClient, cipher, mappingStore, metaStore,
                new ResourceUrlConverter(new ResourceSigner(props)), props);
        this.metaStore = metaStore;
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("generations 同步封装: 轮询至 SUCCEEDED → {created, data:[{url}]}")
    void generationsSuccessReturnsDataUrls() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> processing = new java.util.LinkedHashMap<>();
        processing.put("task_no", "T1");
        processing.put("status", "PROCESSING");
        Map<String, Object> success = new java.util.LinkedHashMap<>();
        success.put("task_no", "T1");
        success.put("status", "SUCCEEDED");
        success.put("result", Map.of("resources", List.of("https://gw/v1/resources/T1/0?exp=1&sig=x")));
        org.mockito.Mockito.doReturn(Mono.just(processing)).doReturn(Mono.just(success))
                .when(spy).poll("image", "T1", "key", null);

        StepVerifier.create(spy.pollUntilTerminal("image", "T1", "key", null,
                        java.time.Instant.now().plusSeconds(5), java.time.Duration.ofMillis(10)))
                .assertNext(resp -> {
                    assertThat(resp.get("created")).isNotNull();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
                    assertThat(data.get(0).get("url").toString()).contains("/v1/resources/T1/0");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("generations 同步封装: 超时降级 {status=PROCESSING, task_no, poll_url}")
    void generationsTimeoutFallsBackToProcessing() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> processing = new java.util.LinkedHashMap<>();
        processing.put("task_no", "T2");
        processing.put("status", "PROCESSING");
        org.mockito.Mockito.doReturn(Mono.just(processing)).when(spy).poll("image", "T2", "key", null);

        StepVerifier.create(spy.pollUntilTerminal("image", "T2", "key", null,
                        java.time.Instant.now().minusSeconds(1), java.time.Duration.ofMillis(10)))
                .assertNext(resp -> {
                    assertThat(resp.get("status")).isEqualTo("PROCESSING");
                    assertThat(resp.get("task_no")).isEqualTo("T2");
                    assertThat(resp.get("poll_url")).isEqualTo("/v1/onetoken/images/T2");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("generations 同步封装: 上游 FAILED → 502 透出错误消息")
    void generationsFailedPropagatesMessage() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> failed = new java.util.LinkedHashMap<>();
        failed.put("task_no", "T3");
        failed.put("status", "FAILED");
        failed.put("error", Map.of("code", "SCRIPT_ERROR", "message", "waibibabo HTTP 502"));
        org.mockito.Mockito.doReturn(Mono.just(failed)).when(spy).poll("image", "T3", "key", null);

        StepVerifier.create(spy.pollUntilTerminal("image", "T3", "key", null,
                        java.time.Instant.now().plusSeconds(5), java.time.Duration.ofMillis(10)))
                .expectErrorSatisfies(e -> {
                    org.assertj.core.api.Assertions.assertThat(e)
                            .isInstanceOf(fun.commons.tokengateway.exception.RelayException.class);
                    org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("HTTP 502");
                })
                .verify();
    }

    @Test
    @DisplayName("generations: n>1 直接拒绝 (任务面单图语义)")
    void generationsRejectsMultiImage() {
        StepVerifier.create(orchestrator.createImageGenerations("key",
                        Map.of("model", "waibibabo-gpt-image-2", "prompt", "x", "n", 2), null, null, null))
                .expectErrorSatisfies(e -> {
                    org.assertj.core.api.Assertions.assertThat(e)
                            .isInstanceOf(fun.commons.tokengateway.exception.RelayException.class);
                    org.assertj.core.api.Assertions.assertThat(e.getMessage()).contains("仅支持 n=1");
                })
                .verify();
    }

    // ---------- issue #19: OpenAI 协议 job 封装 ----------

    @Test
    @DisplayName("openai video job: 建单 → {id, object=video_generation, status=queued}")
    void openAiVideoJobCreateReturnsQueued() {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("vJob1"));
        when(mappingStore.put(anyString(), eq("vJob1"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.createVideoJob("sk-caller",
                        Map.of("model", "sora-2", "prompt", "猫滑滑板", "seconds", "8",
                                "size", "1280x720"), "trace-v", null, null))
                .assertNext(job -> {
                    assertThat(String.valueOf(job.get("id"))).startsWith("T");
                    assertThat(job.get("object")).isEqualTo("video_generation");
                    assertThat(job.get("status")).isEqualTo("queued");
                    assertThat(job.get("created_at")).isNotNull();
                })
                .verifyComplete();
        // sora 参数 → 任务面 params 契约
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lotaskClient).submit(eq("video"), anyString(), payload.capture(), anyString());
        assertThat(payload.getValue().get("params"))
                .isEqualTo(Map.of("prompt", "猫滑滑板", "seconds", "8", "size", "1280x720"));
    }

    @Test
    @DisplayName("openai job 视图: 五态映射 + image completed 带 output 代理 URL + failed 带 error")
    void openAiJobViewMapping() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> running = new java.util.LinkedHashMap<>();
        running.put("task_no", "T9");
        running.put("status", "RUNNING");
        org.mockito.Mockito.doReturn(Mono.just(running)).when(spy).poll("video", "T9", "key", null);
        StepVerifier.create(spy.videoJob("T9", "key", null))
                .assertNext(v -> assertThat(v.get("status")).isEqualTo("in_progress"))
                .verifyComplete();

        Map<String, Object> done = new java.util.LinkedHashMap<>();
        done.put("task_no", "T9");
        done.put("status", "SUCCEEDED");
        done.put("result", Map.of("resources", List.of("/v1/resources/T9/0?exp=1&sig=x")));
        org.mockito.Mockito.doReturn(Mono.just(done)).when(spy).poll("image", "T9", "key", null);
        StepVerifier.create(spy.imageJob("T9", "key", null))
                .assertNext(img -> {
                    assertThat(img.get("status")).isEqualTo("completed");
                    assertThat(img.get("object")).isEqualTo("image_generation");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> output = (List<Map<String, Object>>) img.get("output");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> content = (Map<String, Object>) ((List<?>) output.get(0).get("content")).get(0);
                    assertThat(content.get("type")).isEqualTo("output_image");
                    assertThat(((Map<?, ?>) content.get("image_url")).get("url"))
                            .isEqualTo("/v1/resources/T9/0?exp=1&sig=x");
                })
                .verifyComplete();

        Map<String, Object> failed = new java.util.LinkedHashMap<>();
        failed.put("task_no", "T9");
        failed.put("status", "EXPIRED");
        failed.put("error", Map.of("code", "TIMEOUT", "message", "任务超时"));
        org.mockito.Mockito.doReturn(Mono.just(failed)).when(spy).poll("video", "T9", "key", null);
        StepVerifier.create(spy.videoJob("T9", "key", null))
                .assertNext(v -> {
                    assertThat(v.get("status")).isEqualTo("failed");
                    assertThat(((Map<?, ?>) v.get("error")).get("code")).isEqualTo("TIMEOUT");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("openai video content: SUCCEEDED→307 URL / RUNNING→409 / 无资源→404")
    void openAiVideoContentUrlStates() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> done = new java.util.LinkedHashMap<>();
        done.put("task_no", "T8");
        done.put("status", "SUCCEEDED");
        done.put("result", Map.of("resources", List.of("/v1/resources/T8/0?exp=1&sig=y")));
        org.mockito.Mockito.doReturn(Mono.just(done)).when(spy).poll("video", "T8", "key", null);
        StepVerifier.create(spy.videoContentUrl("T8", "key", null))
                .expectNext("/v1/resources/T8/0?exp=1&sig=y")
                .verifyComplete();

        Map<String, Object> running = new java.util.LinkedHashMap<>();
        running.put("task_no", "T8");
        running.put("status", "RUNNING");
        org.mockito.Mockito.doReturn(Mono.just(running)).when(spy).poll("video", "T8", "key", null);
        StepVerifier.create(spy.videoContentUrl("T8", "key", null))
                .expectErrorSatisfies(e -> assertThat(((RelayException) e).getHttpStatus()).isEqualTo(409))
                .verify();

        Map<String, Object> noRes = new java.util.LinkedHashMap<>();
        noRes.put("task_no", "T8");
        noRes.put("status", "SUCCEEDED");
        org.mockito.Mockito.doReturn(Mono.just(noRes)).when(spy).poll("video", "T8", "key", null);
        StepVerifier.create(spy.videoContentUrl("T8", "key", null))
                .expectErrorSatisfies(e -> assertThat(((RelayException) e).getHttpStatus()).isEqualTo(404))
                .verify();
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
                    assertThat(view.get("poll_url")).isEqualTo("/v1/onetoken/videos/" + taskNo);
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
    @DisplayName("clientIp 透传 (issue #27): create 六参重载 → validate 请求体携带 clientIp")
    void createPassesClientIpToValidate() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("video", "sk-caller",
                        Map.of("model", "kling-v1", "params", Map.of("seconds", 5)), "trace-1",
                        null, "9.9.9.9"))
                .expectNextCount(1)
                .verifyComplete();

        okhttp3.mockwebserver.RecordedRequest validate =
                backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(validate).isNotNull();
        assertThat(validate.getPath()).contains("/api/v1/internal/tokens/validate");
        assertThat(validate.getBody().readUtf8()).contains("\"clientIp\":\"9.9.9.9\"");
    }

    @Test
    @DisplayName("issue #30: 非数字 Idempotency-Key → 正常受理, preConsume requestId 纯数字且 ≠ 键值")
    void createNonNumericIdemKeyNotForwardedToBilling() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("image", "sk-caller",
                        Map.of("model", "token-mock-image-01"), null, "bl11-regression-test-001"))
                .expectNextCount(1)
                .verifyComplete();

        // distribute 仍透传幂等键 (路由去重专用字段, 不进业务参数位)
        backend.takeRequest(); // validate
        okhttp3.mockwebserver.RecordedRequest dist = backend.takeRequest();
        assertThat(dist.getBody().readUtf8()).contains("bl11-regression-test-001");
        // billing preConsume: requestId 必须纯数字且 ≠ 幂等键 (Mmagix workId BIGINT 契约)
        okhttp3.mockwebserver.RecordedRequest preConsume = backend.takeRequest(5,
                java.util.concurrent.TimeUnit.SECONDS);
        JSONObject body = JSON.parseObject(preConsume.getBody().readUtf8());
        String requestId = body.getString("requestId");
        assertThat(requestId).matches("\\d+");
        assertThat(requestId).isNotEqualTo("bl11-regression-test-001");
    }

    @Test
    @DisplayName("issue #12/#30: 纯数字 Idempotency-Key → preConsume requestId = 原值")
    void createNumericIdemKeyForwardedAsBillingRequestId() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("image", "sk-caller",
                        Map.of("model", "token-mock-image-01"), null, "9876543210"))
                .expectNextCount(1)
                .verifyComplete();

        backend.takeRequest(); // validate
        backend.takeRequest(); // distribute
        okhttp3.mockwebserver.RecordedRequest preConsume = backend.takeRequest(5,
                java.util.concurrent.TimeUnit.SECONDS);
        JSONObject body = JSON.parseObject(preConsume.getBody().readUtf8());
        assertThat(body.getString("requestId")).isEqualTo("9876543210");
    }

    @Test
    @DisplayName("issue #13: submit-task-type=model → lotask task_type 取 body.model; poll_url 仍模态")
    void createModelGranularitySubmitType() {
        props.getTask().setSubmitTaskType("model");
        enqueueHappyControlPlane(backend);
        String modelCode = "runninghub-gptimage2-text-to-image";
        when(lotaskClient.submit(eq(modelCode), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("image", "sk-caller",
                        Map.of("model", modelCode), null))
                .assertNext(view -> {
                    String taskNo = (String) view.get("task_no");
                    // 网关 API 面不变: poll_url 仍按模态构造
                    assertThat(view.get("poll_url")).isEqualTo("/v1/onetoken/images/" + taskNo);
                })
                .verifyComplete();

        // lotask task_type = 模型编码 (remote Worker 按 modelCode 认领的前提)
        verify(lotaskClient).submit(eq(modelCode), anyString(), any(), anyString());
        // TaskMeta.modality 同键 (终态 TTL/超时钟按同粒度解析)
        ArgumentCaptor<TaskMetaStore.TaskMeta> meta = ArgumentCaptor.forClass(TaskMetaStore.TaskMeta.class);
        verify(metaStore).onCreated(anyString(), meta.capture(), any());
        assertThat(meta.getValue().modality()).isEqualTo(modelCode);
    }

    @Test
    @DisplayName("计费合规修复: OneToken body.params dims 透传 distribute (复合档计价前提)")
    void createForwardsOneTokenPriceParams() {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("image", "sk-caller",
                        Map.of("model", "runninghub-gptimage2-4k",
                                "params", Map.of("ratio", "1:1", "resolution", "4k",
                                        "referenceImageUrls", java.util.List.of("https://r/1.png"))), null))
                .expectNextCount(1)
                .verifyComplete();

        // distribute 请求体必须带 params.{ratio,resolution} — 协议面按 4K 档计价的前提
        try {
            backend.takeRequest(); // validate
            okhttp3.mockwebserver.RecordedRequest dist = backend.takeRequest(); // distribute
            com.alibaba.fastjson2.JSONObject body =
                    com.alibaba.fastjson2.JSON.parseObject(dist.getBody().readUtf8());
            com.alibaba.fastjson2.JSONObject params = body.getJSONObject("params");
            org.assertj.core.api.Assertions.assertThat(params).isNotNull();
            org.assertj.core.api.Assertions.assertThat(params.getString("resolution")).isEqualTo("4k");
            org.assertj.core.api.Assertions.assertThat(params.getString("ratio")).isEqualTo("1:1");
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("extractPriceParams: params 与顶层同键时顶层覆盖; 无 body 返回空")
    void extractPriceParamsMergesShapes() {
        Map<String, Object> out = TaskRelayOrchestrator.extractPriceParams(
                Map.of("params", Map.of("resolution", "2k", "ratio", "16:9"),
                        "resolution", "4k"));
        org.assertj.core.api.Assertions.assertThat(out)
                .containsEntry("resolution", "4k")     // 顶层 (OpenAI 形状) 优先
                .containsEntry("ratio", "16:9");       // params (OneToken 形状)
        org.assertj.core.api.Assertions.assertThat(
                TaskRelayOrchestrator.extractPriceParams(null)).isEmpty();
    }

    @Test
    @DisplayName("余额不足 → 402 + 10617, 不产生任务 (lotask submit 不调用)")
    void createInsufficientBalance() {        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"t1\","
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
    @DisplayName("未知模型 (bootstrap 20103 MODEL_NOT_FOUND) → 404 + 10400, 不产生任务 (P1-5)")
    void createUnknownModelBootstrap20103() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"t1\","
                + "\"userId\":\"u1\",\"tenantId\":\"tn1\"}}"));
        backend.enqueue(json("{\"code\":20103,\"message\":\"模型不存在或已下线: no-such-model-regression\","
                + "\"data\":null}"));

        StepVerifier.create(orchestrator.create("image", "sk-caller",
                        Map.of("model", "no-such-model-regression"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404
                        && re.getCode() == ApiCode.NOT_FOUND.getCode())
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
    @DisplayName("issue #22: token 校验 RPC 失败 → 504 + 10003 (可重试基础设施错误), 不产生任务")
    void createTokenRpcFailure504() {
        backend.enqueue(new MockResponse().setResponseCode(500)); // token-validate 5xx → fail 包络

        StepVerifier.create(orchestrator.create("image", "sk-caller", Map.of("model", "sd-xl"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 504
                        && re.getCode() == ApiCode.SERVICE_TIMEOUT.getCode())
                .verify();
        verify(lotaskClient, never()).submit(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("issue #22: token 真无效 → 维持 401 + 10202")
    void createInvalidToken401() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":false}}"));

        StepVerifier.create(orchestrator.create("image", "sk-bad", Map.of("model", "sd-xl"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 401
                        && re.getCode() == ApiCode.TOKEN_INVALID.getCode())
                .verify();
    }

    @Test
    @DisplayName("issue #22: poll 时 token 校验 RPC 失败 → 504 + 10003 (不触映射查询)")
    void pollTokenRpcFailure504() {
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(orchestrator.poll("video", "T-x", "sk-caller", null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 504
                        && re.getCode() == ApiCode.SERVICE_TIMEOUT.getCode())
                .verify();
        verify(mappingStore, never()).get(anyString());
    }

    @Test
    @DisplayName("poll: 映射缺失 → 404 + 10400")
    void pollMappingMissing() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-ghost")).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.poll("video", "T-ghost", "sk-caller", null))
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

        StepVerifier.create(orchestrator.poll("video", "T-ok", "sk-caller", null))
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

        StepVerifier.create(orchestrator.poll("video", "T-bad", "sk-caller", null))
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

        StepVerifier.create(orchestrator.poll("video", "T-done", "sk-caller", null))
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
