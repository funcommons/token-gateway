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
        // issue #42: 非终态 poll 补 expires_at 的 meta 读取, 缺省空 (meta 缺失 → 不补键)
        when(metaStore.getMeta(anyString())).thenReturn(Mono.empty());

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
    @DisplayName("tts create: poll_url 单数路径 /v1/onetoken/tts/{task_no}, 不复数化成 ttss (issue #40)")
    void ttsCreatePollUrlSingular() {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("tts"), anyString(), any(), eq("http://gw/internal/lotask/webhook")))
                .thenReturn(Mono.just("LTts1"));
        when(mappingStore.put(anyString(), eq("LTts1"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("tts", "sk-caller",
                        Map.of("model", "tts-1", "input", "你好"), "trace-1"))
                .assertNext(view -> {
                    String taskNo = (String) view.get("task_no");
                    assertThat(taskNo).startsWith("T");
                    assertThat(view.get("status")).isEqualTo("PENDING");
                    assertThat(view.get("poll_url")).isEqualTo("/v1/onetoken/tts/" + taskNo);
                })
                .verifyComplete();
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

    // ---------- issue #36: sora 形状 seconds→duration 计价别名 ----------

    @Test
    @DisplayName("issue #36: 顶层 seconds=\"5\" → duration=\"5\" 别名 (原样透传), seconds 键不进 out")
    void extractPriceParamsTopLevelSecondsAliasesDuration() {
        Map<String, Object> out = TaskRelayOrchestrator.extractPriceParams(
                Map.of("model", "sora-2", "seconds", "5", "size", "1280x720"));
        org.assertj.core.api.Assertions.assertThat(out)
                .containsEntry("duration", "5")
                .containsEntry("size", "1280x720")
                .doesNotContainKey("seconds")
                .hasSize(2);
    }

    @Test
    @DisplayName("issue #36: params 内嵌 seconds (OneToken/sora-job 形状) → duration 别名同效")
    void extractPriceParamsNestedSecondsAliasesDuration() {
        Map<String, Object> out = TaskRelayOrchestrator.extractPriceParams(
                Map.of("model", "sora-2",
                        "params", Map.of("prompt", "猫滑滑板", "seconds", "8", "size", "1280x720")));
        org.assertj.core.api.Assertions.assertThat(out)
                .containsEntry("duration", "8")
                .containsEntry("size", "1280x720")
                .doesNotContainKey("seconds")
                .hasSize(2);
    }

    @Test
    @DisplayName("issue #36: 显式 duration + seconds 并存 → duration 赢 (别名不覆盖显式值)")
    void extractPriceParamsExplicitDurationWinsOverSeconds() {
        Map<String, Object> topLevel = TaskRelayOrchestrator.extractPriceParams(
                Map.of("duration", "10", "seconds", "5"));
        org.assertj.core.api.Assertions.assertThat(topLevel)
                .containsEntry("duration", "10")
                .doesNotContainKey("seconds")
                .hasSize(1);
        Map<String, Object> nested = TaskRelayOrchestrator.extractPriceParams(
                Map.of("params", Map.of("duration", 12, "seconds", 5)));
        org.assertj.core.api.Assertions.assertThat(nested)
                .containsEntry("duration", 12)
                .doesNotContainKey("seconds")
                .hasSize(1);
    }

    @Test
    @DisplayName("issue #36 回归: OneToken params 四键逐字节不变 (无 seconds 时别名单纯空转)")
    void extractPriceParamsOneTokenFourKeysUnchanged() {
        Map<String, Object> out = TaskRelayOrchestrator.extractPriceParams(
                Map.of("params", Map.of("size", "1024x1024", "ratio", "1:1",
                        "resolution", "4k", "duration", 5,
                        "referenceImageUrls", java.util.List.of("https://r/1.png"))));
        org.assertj.core.api.Assertions.assertThat(out)
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "size", "1024x1024", "ratio", "1:1", "resolution", "4k", "duration", 5));
    }

    @Test
    @DisplayName("issue #36 端到端: sora createVideoJob seconds=\"5\" → distribute 请求体 params.duration=\"5\"")
    void createVideoJobSecondsFlowsToDistributeDuration() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("vJob36"));
        when(mappingStore.put(anyString(), eq("vJob36"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.createVideoJob("sk-caller",
                        Map.of("model", "sora-2", "prompt", "猫滑滑板", "seconds", "5",
                                "size", "1280x720"), "trace-36", null, null))
                .expectNextCount(1)
                .verifyComplete();

        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // validate
        okhttp3.mockwebserver.RecordedRequest dist =
                backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(dist).isNotNull();
        com.alibaba.fastjson2.JSONObject body =
                com.alibaba.fastjson2.JSON.parseObject(dist.getBody().readUtf8());
        com.alibaba.fastjson2.JSONObject params = body.getJSONObject("params");
        org.assertj.core.api.Assertions.assertThat(params).isNotNull();
        org.assertj.core.api.Assertions.assertThat(params.getString("duration")).isEqualTo("5");
        org.assertj.core.api.Assertions.assertThat(params.getString("size")).isEqualTo("1280x720");
        org.assertj.core.api.Assertions.assertThat(params.containsKey("seconds")).isFalse();
    }

    // ---------- issue #37: distribute 携带 clientIp ----------

    @Test
    @DisplayName("clientIp 透传 (issue #37): create → distribute 请求体携带 clientIp")
    void createPassesClientIpToDistribute() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("video", "sk-caller",
                        Map.of("model", "kling-v1", "params", Map.of("seconds", 5)), "trace-1",
                        null, "9.9.9.9"))
                .expectNextCount(1)
                .verifyComplete();

        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // validate
        okhttp3.mockwebserver.RecordedRequest dist =
                backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(dist).isNotNull();
        com.alibaba.fastjson2.JSONObject body =
                com.alibaba.fastjson2.JSON.parseObject(dist.getBody().readUtf8());
        org.assertj.core.api.Assertions.assertThat(body.getString("clientIp")).isEqualTo("9.9.9.9");
    }

    @Test
    @DisplayName("clientIp 缺省 (issue #37): create 不带 clientIp → distribute 请求体 clientIp 为 null/缺席, 序列化不炸")
    void createWithoutClientIpDistributeBodySafe() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("video", "sk-caller",
                        Map.of("model", "kling-v1"), "trace-1"))
                .expectNextCount(1)
                .verifyComplete();

        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // validate
        okhttp3.mockwebserver.RecordedRequest dist =
                backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        org.assertj.core.api.Assertions.assertThat(dist).isNotNull();
        com.alibaba.fastjson2.JSONObject body =
                com.alibaba.fastjson2.JSON.parseObject(dist.getBody().readUtf8());
        org.assertj.core.api.Assertions.assertThat(body.get("clientIp")).isNull();
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

    // ---------- B-14: validate fail 信封映射矩阵 (fail 401/402 终态, 其余 504 可重试) ----------

    @Test
    @DisplayName("B-14: validate fail(401) key 禁用 → 终态 401 + 10202 + 信封 message, 不产生任务")
    void createValidateFail401Terminal() {
        backend.enqueue(json("{\"code\":401,\"message\":\"API Key 已禁用或过期\"}"));

        StepVerifier.create(orchestrator.create("image", "sk-bad", Map.of("model", "sd-xl"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 401
                        && re.getCode() == ApiCode.TOKEN_INVALID.getCode()
                        && "API Key 已禁用或过期".equals(re.getMessage()))
                .verify();
        verify(lotaskClient, never()).submit(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("B-14: validate fail(402) 配额耗尽 → 终态 402 + 10617 + 信封 message, 不产生任务")
    void createValidateFail402Terminal() {
        backend.enqueue(json("{\"code\":402,\"message\":\"API Key 配额已耗尽\"}"));

        StepVerifier.create(orchestrator.create("image", "sk-poor", Map.of("model", "sd-xl"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402
                        && re.getCode() == ApiCode.INSUFFICIENT_BALANCE.getCode()
                        && "API Key 配额已耗尽".equals(re.getMessage()))
                .verify();
        verify(lotaskClient, never()).submit(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("B-14: validate fail(10001) 非永久码 → 维持 504 可重试 (真基础设施错误语义不回退)")
    void createValidateFailOtherCodeStill504() {
        backend.enqueue(json("{\"code\":10001,\"message\":\"系统繁忙，请稍后再试\"}"));

        StepVerifier.create(orchestrator.create("image", "sk-x", Map.of("model", "sd-xl"), null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 504
                        && re.getCode() == ApiCode.SERVICE_TIMEOUT.getCode())
                .verify();
        verify(lotaskClient, never()).submit(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("B-14: poll 时 validate fail(401/402) 同样终态 401/402")
    void pollValidateFail401And402Terminal() {
        backend.enqueue(json("{\"code\":401,\"message\":\"API Key 已禁用或过期\"}"));
        StepVerifier.create(orchestrator.poll("video", "T-x", "sk-bad", null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 401
                        && re.getCode() == ApiCode.TOKEN_INVALID.getCode())
                .verify();

        backend.enqueue(json("{\"code\":402,\"message\":\"API Key 配额已耗尽\"}"));
        StepVerifier.create(orchestrator.poll("video", "T-x", "sk-poor", null))
                .expectErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402
                        && re.getCode() == ApiCode.INSUFFICIENT_BALANCE.getCode())
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

    @Test
    @DisplayName("poll: 旧格式终态条目 (24h 前代理 URL) 读时重签 — sig 换新 exp 新鲜 (issue #43 存量自愈)")
    void pollTerminalEntryLegacyProxyResignsFresh() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-old")).thenReturn(Mono.just("YeirYkxHuQ"));
        when(metaStore.getTerminalResult("T-old")).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"SUCCEEDED\","
                        + "\"result\":{\"resources\":[\"/v1/resources/T-old/0?exp=1&sig=stale\"]}}")));

        StepVerifier.create(orchestrator.poll("video", "T-old", "sk-caller", null))
                .assertNext(view -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) view.get("result");
                    String url = String.valueOf(
                            ((java.util.List<?>) result.get("resources")).get(0));
                    assertThat(url).startsWith("/v1/resources/T-old/0?exp=");
                    assertThat(url).doesNotContain("sig=stale").doesNotContain("exp=1&");
                    long exp = Long.parseLong(url.replaceAll(".*[?&]exp=(\\d+).*", "$1"));
                    assertThat(exp).isGreaterThan(System.currentTimeMillis() / 1000);
                })
                .verifyComplete();
        verify(lotaskClient, never()).get(anyString());
    }

    @Test
    @DisplayName("poll: 新格式终态条目 (存原始/改写后 URL, issue #43 翻转) 读时现签代理 URL, 原始值不透出")
    void pollTerminalEntryNewFormatSignsAtRead() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-new")).thenReturn(Mono.just("YeirYkxHuQ"));
        when(metaStore.getTerminalResult("T-new")).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"SUCCEEDED\","
                        + "\"result\":{\"resources\":[\"https://consumer-oss/rewritten.mp4\"],"
                        + "\"filtered_by\":\"consumer\"}}")));

        StepVerifier.create(orchestrator.poll("video", "T-new", "sk-caller", null))
                .assertNext(view -> {
                    assertThat(view.get("status")).isEqualTo("SUCCEEDED");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> result = (Map<String, Object>) view.get("result");
                    String url = String.valueOf(
                            ((java.util.List<?>) result.get("resources")).get(0));
                    assertThat(url).startsWith("/v1/resources/T-new/0?exp=");
                    assertThat(url).doesNotContain("consumer-oss");
                    // 非 resources 字段原样透出 (改写留痕可见)
                    assertThat(result.get("filtered_by")).isEqualTo("consumer");
                })
                .verifyComplete();
        verify(lotaskClient, never()).get(anyString());
    }

    // ---------- issue #41: buildPayload 双锚 (tenantId / billingRequestId) ----------

    @Test
    @DisplayName("issue #41+#42: create e2e — payload 双锚与 validate/preConsume 链一致; createdView expires_at=超时钟 deadline (ISO-8601)")
    void createPayloadAnchorsAndExpiresAt() throws InterruptedException {
        props.getTask().setTimeouts(Map.of("video", Duration.ofHours(2)));
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        String[] expiresAtHolder = new String[1];
        StepVerifier.create(orchestrator.create("video", "sk-caller",
                        Map.of("model", "kling-v1", "params", Map.of("seconds", 5)), null,
                        "1234567890", null))
                .assertNext(view -> {
                    assertThat(view.get("task_no").toString()).startsWith("T");
                    assertThat(view.get("status")).isEqualTo("PENDING");
                    // expires_at: ISO-8601 UTC, 落在 video 2h 超时窗内
                    Object expiresAt = view.get("expires_at");
                    assertThat(expiresAt).isInstanceOf(String.class);
                    java.time.Instant parsed = java.time.Instant.parse((String) expiresAt);
                    assertThat(parsed).isAfter(java.time.Instant.now());
                    assertThat(parsed).isBefore(java.time.Instant.now()
                            .plus(Duration.ofHours(2)).plusSeconds(60));
                    expiresAtHolder[0] = (String) expiresAt;
                })
                .verifyComplete();

        // 双锚 (#41): tenantId = validate 响应 tn1; billingRequestId = 数字幂等键原值 (#30 口径)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lotaskClient).submit(eq("video"), anyString(), payload.capture(), anyString());
        assertThat(payload.getValue().get("tenantId")).isEqualTo("tn1");
        assertThat(payload.getValue().get("billingRequestId")).isEqualTo("1234567890");

        // 与计费链一致: preConsume requestId 同值
        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // validate
        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // distribute
        RecordedRequest preConsume = backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(preConsume).isNotNull();
        JSONObject preConsumeBody = JSON.parseObject(preConsume.getBody().readUtf8());
        assertThat(preConsumeBody.getString("requestId")).isEqualTo("1234567890");

        // expires_at 与 create 写入 meta 的 deadline 同值 (同一真源, 非造数)
        ArgumentCaptor<TaskMetaStore.TaskMeta> meta =
                ArgumentCaptor.forClass(TaskMetaStore.TaskMeta.class);
        verify(metaStore).onCreated(anyString(), meta.capture(), any());
        assertThat(expiresAtHolder[0]).isEqualTo(
                java.time.Instant.ofEpochMilli(meta.getValue().deadlineEpochMs()).toString());
    }

    @Test
    @DisplayName("issue #41: 非数字 trace 无幂等键 → billingRequestId 为网关生成纯数字串且 = preConsume requestId")
    void createPayloadBillingRequestIdGeneratedMatchesPreConsume() throws InterruptedException {
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(anyString(), anyString(), any(), anyString()))
                .thenReturn(Mono.just("YeirYkxHuQ"));
        when(mappingStore.put(anyString(), eq("YeirYkxHuQ"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.create("image", "sk-caller",
                        Map.of("model", "token-mock-image-01"), "trace-non-numeric"))
                .expectNextCount(1)
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(lotaskClient).submit(anyString(), anyString(), payload.capture(), anyString());
        String billingRequestId = (String) payload.getValue().get("billingRequestId");
        assertThat(billingRequestId).matches("\\d+");
        assertThat(payload.getValue().get("tenantId")).isEqualTo("tn1");

        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // validate
        backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS); // distribute
        RecordedRequest preConsume = backend.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(preConsume).isNotNull();
        JSONObject preConsumeBody = JSON.parseObject(preConsume.getBody().readUtf8());
        assertThat(preConsumeBody.getString("requestId")).isEqualTo(billingRequestId);
    }

    // ---------- issue #42: poll / 视图 expires_at ----------

    @Test
    @DisplayName("issue #42: poll 非终态 meta 存在 → expires_at 透出 (ISO-8601), 值=create 写入的 deadline")
    void pollRunningCarriesExpiresAtFromMeta() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-run")).thenReturn(Mono.just("YeirYkxHuQ"));
        long deadline = System.currentTimeMillis() + Duration.ofHours(2).toMillis();
        when(metaStore.getMeta("T-run")).thenReturn(Mono.just(new TaskMetaStore.TaskMeta(
                "YeirYkxHuQ", "pc1", "video", null, deadline, "sk-upstream")));
        when(lotaskClient.get("YeirYkxHuQ")).thenReturn(Mono.just(new LotaskTaskView(
                "YeirYkxHuQ", "RUNNING", null, null, null)));

        StepVerifier.create(orchestrator.poll("video", "T-run", "sk-caller", null))
                .assertNext(view -> {
                    assertThat(view.get("status")).isEqualTo("RUNNING");
                    assertThat(view.get("expires_at"))
                            .isEqualTo(java.time.Instant.ofEpochMilli(deadline).toString());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("issue #42: poll meta 缺失 (TTL 过期/历史任务) → 无 expires_at 键, 主链路不炸 (不造数)")
    void pollMetaMissingOmitsExpiresAt() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-old")).thenReturn(Mono.just("YeirYkxHuQ"));
        // setUp 默认 getMeta → Mono.empty (meta 缺失)
        when(lotaskClient.get("YeirYkxHuQ")).thenReturn(Mono.just(new LotaskTaskView(
                "YeirYkxHuQ", "RUNNING", null, null, null)));

        StepVerifier.create(orchestrator.poll("video", "T-old", "sk-caller", null))
                .assertNext(view -> {
                    assertThat(view.get("status")).isEqualTo("RUNNING");
                    assertThat(view).doesNotContainKey("expires_at");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("issue #42: 终态条目视图不含 expires_at (字段集不变: result/error 原样回放)")
    void pollTerminalEntryHasNoExpiresAt() {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true}}"));
        when(mappingStore.get("T-done2")).thenReturn(Mono.just("YeirYkxHuQ"));
        when(metaStore.getTerminalResult("T-done2")).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"FAILED\","
                        + "\"error\":{\"code\":\"UPSTREAM_ERROR\",\"message\":\"上游超时\"}}")));

        StepVerifier.create(orchestrator.poll("video", "T-done2", "sk-caller", null))
                .assertNext(view -> {
                    assertThat(view.get("status")).isEqualTo("FAILED");
                    assertThat(view).doesNotContainKey("expires_at");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> error = (Map<String, Object>) view.get("error");
                    assertThat(error.get("code")).isEqualTo("UPSTREAM_ERROR");
                })
                .verifyComplete();
        verify(lotaskClient, never()).get(anyString());
    }

    @Test
    @DisplayName("issue #42: generations 超时降级视图透传 expires_at (末次 poll 视图携带时); 不携带则无此键")
    void generationsProcessingFallbackCarriesExpiresAt() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> processing = new java.util.LinkedHashMap<>();
        processing.put("task_no", "T5");
        processing.put("status", "PROCESSING");
        processing.put("expires_at", "2026-09-29T13:00:00Z");
        org.mockito.Mockito.doReturn(Mono.just(processing)).when(spy).poll("image", "T5", "key", null);

        StepVerifier.create(spy.pollUntilTerminal("image", "T5", "key", null,
                        java.time.Instant.now().minusSeconds(1), java.time.Duration.ofMillis(10)))
                .assertNext(resp -> {
                    assertThat(resp.get("status")).isEqualTo("PROCESSING");
                    assertThat(resp.get("poll_url")).isEqualTo("/v1/onetoken/images/T5");
                    assertThat(resp.get("expires_at")).isEqualTo("2026-09-29T13:00:00Z");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("issue #42: openai job 轮询视图 expires_at 转 epoch 秒 (与 OneToken ISO-8601 区分); 无该键不补")
    void openAiJobViewExpiresAtEpochSeconds() {
        TaskRelayOrchestrator spy = org.mockito.Mockito.spy(orchestrator);
        Map<String, Object> running = new java.util.LinkedHashMap<>();
        running.put("task_no", "T9");
        running.put("status", "RUNNING");
        running.put("expires_at", "2026-09-29T13:00:00Z");
        org.mockito.Mockito.doReturn(Mono.just(running)).when(spy).poll("video", "T9", "key", null);
        StepVerifier.create(spy.videoJob("T9", "key", null))
                .assertNext(v -> assertThat(v.get("expires_at")).isEqualTo(
                        java.time.Instant.parse("2026-09-29T13:00:00Z").getEpochSecond()))
                .verifyComplete();

        Map<String, Object> noExp = new java.util.LinkedHashMap<>();
        noExp.put("task_no", "T9");
        noExp.put("status", "RUNNING");
        org.mockito.Mockito.doReturn(Mono.just(noExp)).when(spy).poll("video", "T9", "key", null);
        StepVerifier.create(spy.videoJob("T9", "key", null))
                .assertNext(v -> assertThat(v).doesNotContainKey("expires_at"))
                .verifyComplete();
    }

    @Test
    @DisplayName("issue #42: openai video job 创建视图携带 expires_at (epoch 秒, video 2h 超时窗内)")
    void openAiVideoJobCreateCarriesExpiresAt() {
        props.getTask().setTimeouts(Map.of("video", Duration.ofHours(2)));
        enqueueHappyControlPlane(backend);
        when(lotaskClient.submit(eq("video"), anyString(), any(), anyString()))
                .thenReturn(Mono.just("vJob42"));
        when(mappingStore.put(anyString(), eq("vJob42"), any())).thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.createVideoJob("sk-caller",
                        Map.of("model", "sora-2", "prompt", "猫滑滑板"), "trace-42", null, null))
                .assertNext(job -> {
                    assertThat(job.get("expires_at")).isInstanceOf(Long.class);
                    long exp = ((Number) job.get("expires_at")).longValue();
                    assertThat(exp).isGreaterThan(java.time.Instant.now().getEpochSecond());
                    assertThat(exp).isLessThanOrEqualTo(java.time.Instant.now()
                            .plus(Duration.ofHours(2)).getEpochSecond() + 60);
                })
                .verifyComplete();
    }
}
