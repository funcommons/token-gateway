package fun.commons.tokengateway.task.notify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import fun.commons.tokengateway.rpc.HttpResultFilterApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.ResourceUrlConverter;
import fun.commons.tokengateway.task.billing.TaskBillingSaga;
import fun.commons.tokengateway.task.log.TaskAccessLogger;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TerminalEventHandler × result-filter (issue #43): SUCCEEDED 落库前回调消费方改写 result.
 * MockWebServer 扮消费方 (能力面); metaStore/billingSaga/notifyDispatcher mock.
 * 覆盖: 开关默认关零变化 / 改写 / 直通 / 失败重试 (settle 解耦照常) / 死信 fail-open /
 * url 缺省回退 backend 装配自洽.
 *
 * <p>存储格式翻转 (issue #43 根治): 终态条目断言均为<b>原始 (改写后) 值</b> —
 * 签名代理 URL 只在 notify body (发送时转换) 与 poll 视图 (读时现签) 出现.
 */
@DisplayName("TerminalEventHandler × result-filter (issue #43)")
class TerminalEventHandlerResultFilterTest {

    private MockWebServer server;
    private TaskMetaStore metaStore;
    private TaskBillingSaga billingSaga;
    private NotifyDispatcher notifyDispatcher;
    private TaskAccessLogger taskAccessLogger;
    private TokenGatewayProperties props;
    private GatewayProperties legacy;
    private Logger deadLetterLogger;
    private ListAppender<ILoggingEvent> deadLetterLog;

    /** 八参形态 (issue #43 起): tenantId/model 落账, 供改写回调载荷断言. */
    private static final TaskMeta META = new TaskMeta("lotask-id-1", "pc1", "video",
            "https://caller/cb", 0L, null, "tenant-9", "vid-1.5");

    private static final Map<String, Object> RAW_RESULT = Map.of(
            "resources", List.of("https://upstream/raw1.mp4", "https://upstream/raw2.mp4"),
            "usage", Map.of("seconds", 5));

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        metaStore = mock(TaskMetaStore.class);
        billingSaga = mock(TaskBillingSaga.class);
        notifyDispatcher = mock(NotifyDispatcher.class);
        taskAccessLogger = mock(TaskAccessLogger.class);
        props = new TokenGatewayProperties();
        props.getTask().setResourceSignKey("test-sign-key");
        legacy = new GatewayProperties();
        legacy.setUrl("http://localhost:1"); // 不可达占位: 各测试按需指向 server
        legacy.setInternalToken("");
        when(billingSaga.settleOnce(anyString(), anyString())).thenReturn(Mono.empty());
        when(billingSaga.refundOnce(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(metaStore.saveTerminalResult(anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(metaStore.clearDeadline(anyString())).thenReturn(Mono.empty());
        when(metaStore.closePending(anyString())).thenReturn(Mono.empty());
        when(metaStore.incrResultFilterAttempts(anyString(), any())).thenReturn(Mono.just(1L));

        deadLetterLogger = (Logger) LoggerFactory.getLogger(ResultFilterDeadLetters.class);
        deadLetterLog = new ListAppender<>();
        deadLetterLog.start();
        deadLetterLogger.addAppender(deadLetterLog);
    }

    @AfterEach
    void tearDown() throws Exception {
        deadLetterLogger.detachAppender(deadLetterLog);
        server.shutdown();
    }

    /** 七参装配 (result-filter 真实 RPC 实例, 寻址 props/legacy). */
    private TerminalEventHandler handler() {
        CapabilityEndpoints endpoints = new CapabilityEndpoints(props, legacy);
        HttpResultFilterApi api = new HttpResultFilterApi(WebClient.builder(), endpoints,
                new RpcInternalAuth(legacy));
        TerminalResultFilter filter = new TerminalResultFilter(api, metaStore, props);
        return new TerminalEventHandler(metaStore, billingSaga, notifyDispatcher,
                new ResourceUrlConverter(new ResourceSigner(props)), props, taskAccessLogger, filter);
    }

    private String serverBase() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    private static MockResponse envelope(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(body);
    }

    private String savedEntry(String taskNo) {
        org.mockito.ArgumentCaptor<String> saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(metaStore).saveTerminalResult(eq(taskNo), saved.capture(), any());
        return saved.getValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> notifyResult(String taskNo) {
        org.mockito.ArgumentCaptor<Map<String, Object>> body =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(notifyDispatcher).dispatch(eq(taskNo), eq("https://caller/cb"), body.capture());
        return (Map<String, Object>) body.getValue().get("result");
    }

    @Test
    @DisplayName("开关默认关 (配了 url 也不发 RPC): 原始 result 落库 + notify 转代理 URL —— 回归红线")
    void disabledByDefaultZeroRpc() {
        props.getResultFilter().setUrl(serverBase()); // enabled 缺省 false
        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        assertThat(server.getRequestCount()).isZero();
        // 存储格式翻转: 终态条目存原始值, 无代理路径
        String entry = savedEntry("T1");
        assertThat(entry).contains("\"status\":\"SUCCEEDED\"");
        assertThat(entry).contains("https://upstream/raw1.mp4").contains("https://upstream/raw2.mp4");
        assertThat(entry).doesNotContain("/v1/resources/");
        // notify body 发送时转换为新鲜签名代理 URL
        assertThat(String.valueOf(notifyResult("T1").get("resources")))
                .contains("/v1/resources/T1/0?exp=").contains("/v1/resources/T1/1?exp=")
                .doesNotContain("https://upstream/");
        verify(billingSaga).settleOnce("pc1", "T1");
        verify(metaStore, never()).incrResultFilterAttempts(anyString(), any());
    }

    @Test
    @DisplayName("开启+改写: 请求载原始 result (裸 URL/tenantId/model), 终态条目存改写后原始值, notify 为代理 URL")
    void enabledRewriteFlowsThroughConversion() throws Exception {
        props.getResultFilter().setEnabled(true);
        props.getResultFilter().setUrl(serverBase());
        server.enqueue(envelope("{\"code\":0,\"message\":\"ok\",\"data\":{\"result\":{"
                + "\"resources\":[\"https://consumer-oss/rewritten.mp4\"],"
                + "\"usage\":{\"seconds\":5},\"filtered_by\":\"consumer\"}}}"));

        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        // 请求契约: 原始 result (上游裸 URL) + taskNo/tenantId/model/status
        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/v1/internal/tasks/result-filter");
        JSONObject body = JSON.parseObject(req.getBody().readUtf8());
        assertThat(body.getString("taskNo")).isEqualTo("T1");
        assertThat(body.getString("requestId")).isEqualTo("T1");
        assertThat(body.getString("tenantId")).isEqualTo("tenant-9");
        assertThat(body.getString("modality")).isEqualTo("video");
        assertThat(body.getString("model")).isEqualTo("vid-1.5");
        assertThat(body.getString("status")).isEqualTo("SUCCEEDED");
        assertThat(body.getJSONObject("result").getJSONArray("resources").getString(0))
                .isEqualTo("https://upstream/raw1.mp4");

        // 终态条目: 改写后原始值 (消费方 OSS URL, 供资源代理直读回源 — 30 天保留闭环)
        String entry = savedEntry("T1");
        assertThat(entry).contains("\"status\":\"SUCCEEDED\"");
        assertThat(entry).contains("https://consumer-oss/rewritten.mp4");
        assertThat(entry).contains("filtered_by");
        assertThat(entry).doesNotContain("https://upstream/").doesNotContain("/v1/resources/");

        // notify body: 改写后值发送时转代理 URL (1 个资源)
        Map<String, Object> result = notifyResult("T1");
        assertThat((List<String>) result.get("resources")).hasSize(1)
                .allSatisfy(u -> assertThat(u).startsWith("/v1/resources/T1/0"));
        assertThat(result.get("filtered_by")).isEqualTo("consumer");
        verify(billingSaga).settleOnce("pc1", "T1");
    }

    @Test
    @DisplayName("data:null / data.result:null = 不改写直通落原值 (终态条目存渠道原始值)")
    void nullDataPassesThrough() {
        props.getResultFilter().setEnabled(true);
        props.getResultFilter().setUrl(serverBase());
        server.enqueue(envelope("{\"code\":0,\"message\":\"ok\",\"data\":null}"));
        server.enqueue(envelope("{\"code\":0,\"message\":\"ok\",\"data\":{\"result\":null}}"));

        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();
        StepVerifier.create(handler().onTerminal("T2", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
        for (String taskNo : List.of("T1", "T2")) {
            String entry = savedEntry(taskNo);
            assertThat(entry).contains("https://upstream/raw1.mp4")
                    .contains("https://upstream/raw2.mp4")
                    .contains("\"seconds\":5");
            assertThat(entry).doesNotContain("/v1/resources/");
        }
    }

    @Test
    @DisplayName("RPC 失败: 不落终态/不通知/不清索, settle 照常发出, attempts INCR; 重放重试成功后落改写后原始值")
    void rpcFailureHoldsTerminalButSettles() {
        props.getResultFilter().setEnabled(true);
        props.getResultFilter().setUrl(serverBase());
        server.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        // 不落终态条目, 不清 deadline/pending, 不 notify —— 任务维持非终态待重放
        verify(metaStore, never()).saveTerminalResult(anyString(), anyString(), any());
        verify(metaStore, never()).clearDeadline(anyString());
        verify(metaStore, never()).closePending(anyString());
        verify(notifyDispatcher, never()).dispatch(anyString(), anyString(), any());
        // settle 与改写解耦: 照常发出 (上游已交付=该收钱)
        verify(billingSaga).settleOnce("pc1", "T1");
        verify(metaStore).incrResultFilterAttempts(eq("T1"), any());
        assertThat(deadLetterLog.list).isEmpty();

        // 对账/超时钟重放终态事件 → 重试成功 → 落库 (改写后原始值)
        server.enqueue(envelope("{\"code\":0,\"data\":{\"result\":{"
                + "\"resources\":[\"https://consumer-oss/rewritten.mp4\"]}}}"));
        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();
        String entry = savedEntry("T1");
        assertThat(entry).contains("https://consumer-oss/rewritten.mp4")
                .doesNotContain("https://upstream/");
        verify(metaStore).clearDeadline("T1");
        verify(metaStore).closePending("T1");
        verify(notifyDispatcher).dispatch(eq("T1"), eq("https://caller/cb"), any());
    }

    @Test
    @DisplayName("attempts 超 max-attempts: [ResultFilter-DeadLetter] 单行 JSON 死信 + fail-open 落原值 (settle/notify 照常)")
    void attemptsExhaustedDeadLettersAndFailsOpen() {
        props.getResultFilter().setEnabled(true);
        props.getResultFilter().setUrl(serverBase());
        when(metaStore.incrResultFilterAttempts(anyString(), any())).thenReturn(Mono.just(6L));
        server.enqueue(envelope("{\"code\":10400,\"message\":\"consumer busy\"}"));

        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        // fail-open: 渠道原始值落终态, 收口链完整
        String entry = savedEntry("T1");
        assertThat(entry).contains("https://upstream/raw1.mp4").contains("https://upstream/raw2.mp4");
        verify(billingSaga).settleOnce("pc1", "T1");
        verify(notifyDispatcher).dispatch(eq("T1"), eq("https://caller/cb"), any());

        // 死信: 单行 JSON, 含 taskNo/attempts/result 原值/错误
        assertThat(deadLetterLog.list).as("死信日志应已落一条").isNotEmpty();
        String formatted = deadLetterLog.list.get(deadLetterLog.list.size() - 1).getFormattedMessage();
        assertThat(formatted).startsWith("[ResultFilter-DeadLetter] ");
        assertThat(formatted).doesNotContain("\n");
        JSONObject payload = JSON.parseObject(formatted.substring("[ResultFilter-DeadLetter] ".length()));
        assertThat(payload.getString("event")).isEqualTo("result-filter-dead-letter");
        assertThat(payload.getString("taskNo")).isEqualTo("T1");
        assertThat(payload.getLongValue("attempts")).isEqualTo(6L);
        assertThat(payload.getString("reason")).contains("10400");
        assertThat(payload.getJSONObject("result").getJSONArray("resources").getString(0))
                .isEqualTo("https://upstream/raw1.mp4");
    }

    @Test
    @DisplayName("enabled=true 但 url 未配: 回退 backend.url + 默认端点路径 (装配自洽)")
    void enabledWithoutUrlFallsBackToBackend() throws Exception {
        legacy.setUrl(serverBase()); // backend 平移目标
        props.getResultFilter().setEnabled(true); // url 不配
        server.enqueue(envelope("{\"code\":0,\"data\":{\"result\":{"
                + "\"resources\":[\"https://consumer-oss/rewritten.mp4\"]}}}"));

        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        RecordedRequest req = server.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/v1/internal/tasks/result-filter");
        assertThat(savedEntry("T1")).contains("https://consumer-oss/rewritten.mp4")
                .doesNotContain("https://upstream/");
    }

    @Test
    @DisplayName("信封 code≠0 同走失败语义: 不落终态 + attempts INCR (业务拒绝与基础设施失败同口径重试)")
    void businessCodeFailureHoldsTerminal() {
        props.getResultFilter().setEnabled(true);
        props.getResultFilter().setUrl(serverBase());
        server.enqueue(envelope("{\"code\":10500,\"message\":\"rewrite declined\"}"));

        StepVerifier.create(handler().onTerminal("T1", META, "SUCCESS", RAW_RESULT))
                .verifyComplete();

        verify(metaStore, never()).saveTerminalResult(anyString(), anyString(), any());
        verify(notifyDispatcher, never()).dispatch(anyString(), anyString(), any());
        verify(billingSaga).settleOnce("pc1", "T1");
        verify(metaStore).incrResultFilterAttempts(eq("T1"), any());
        assertThat(deadLetterLog.list).isEmpty();
    }

    @Test
    @DisplayName("FAILED 终态不过滤: 开关开启也不发 result-filter RPC (原链路不变)")
    void failedStatusNeverFiltered() {
        props.getResultFilter().setEnabled(true);
        props.getResultFilter().setUrl(serverBase());

        StepVerifier.create(handler().onTerminal("T1", META, "FAILED", null))
                .verifyComplete();

        assertThat(server.getRequestCount()).isZero();
        verify(billingSaga).refundOnce("pc1", "task FAILED", "T1");
        verify(billingSaga, never()).settleOnce(anyString(), anyString());
        verify(metaStore).saveTerminalResult(eq("T1"), any(), any());
    }
}
