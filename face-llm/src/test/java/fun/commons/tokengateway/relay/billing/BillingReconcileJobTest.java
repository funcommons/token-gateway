package fun.commons.tokengateway.relay.billing;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BillingReconcileJob 单测 (issue #23): 到期 pending → 按原参数重放 (MockWebServer 断言
 * settle/refund 请求体逐字段一致, 幂等锚 preConsumeId 不变) → 成功 zrem / 失败退避重排 /
 * 超限·业务拒绝死信 (logback list appender 断言单行 JSON 快照).
 */
@DisplayName("BillingReconcileJob")
class BillingReconcileJobTest {

    private MockWebServer backend;
    private BillingPendingStore pendingStore;
    private BillingReconcileJob job;
    private ListAppender<ILoggingEvent> deadLetterLog;
    private Logger deadLetterLogger;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        var billingApi = new HttpBillingApi(WebClient.builder(),
                new CapabilityEndpoints(new TokenGatewayProperties(), props),
                new RpcInternalAuth(props));
        pendingStore = mock(BillingPendingStore.class);
        when(pendingStore.remove(any())).thenReturn(Mono.just(1L));
        when(pendingStore.reschedule(any(), any(), anyLong())).thenReturn(Mono.just(true));
        when(pendingStore.duePending(anyInt())).thenReturn(Flux.empty());
        job = new BillingReconcileJob(pendingStore, billingApi, new BillingReconcileProperties());

        deadLetterLogger = (Logger) LoggerFactory.getLogger(BillingDeadLetters.class);
        deadLetterLog = new ListAppender<>();
        deadLetterLog.start();
        deadLetterLogger.addAppender(deadLetterLog);
    }

    @AfterEach
    void tearDown() throws Exception {
        deadLetterLogger.detachAppender(deadLetterLog);
        backend.shutdown();
    }

    private static List<SettleRequest.AttemptDetail> attempts() {
        return List.of(SettleRequest.AttemptDetail.builder()
                .sequence(1).channelId("c1").model("gpt-4o-mini")
                .errorClass("HTTP_502").billed(true)
                .promptTokens(10).completionTokens(0)
                .build());
    }

    private static BillingPendingStore.BillingPendingEntry entry(BillingPendingRecord record) {
        return new BillingPendingStore.BillingPendingEntry(record, JSON.toJSONString(record));
    }

    private JSONObject deadLetterPayload() {
        List<ILoggingEvent> events = deadLetterLog.list;
        assertThat(events).as("死信日志应已落一条").isNotEmpty();
        String formatted = events.get(events.size() - 1).getFormattedMessage();
        assertThat(formatted).startsWith("[Billing-DeadLetter] ");
        String json = formatted.substring("[Billing-DeadLetter] ".length());
        assertThat(json).doesNotContain("\n");
        return JSON.parseObject(json);
    }

    @Test
    @DisplayName("到期 settle 重放成功: 请求体与原始入队参数逐字段一致 (幂等锚 preConsumeId 不变) → zrem")
    void replaySuccessRemovesPendingAndReplaysExactParams() throws Exception {
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-1", "req-1", 7L, 100, 20, 30, 5, 8L, 9L, 1234, attempts());
        when(pendingStore.duePending(anyInt()))
                .thenReturn(Flux.just(entry(record)));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"creditConsumed\":0.5}}"));

        job.reconcile();

        verify(pendingStore, timeout(2000)).remove(entry(record));
        verify(pendingStore, never()).reschedule(any(), any(), anyLong());

        RecordedRequest request = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        JSONObject body = JSON.parseObject(request.getBody().readUtf8());
        // 幂等锚与全部结算参数逐字段 = 入队快照 (issue #23 验收: 重放同参, 能力面幂等)
        assertThat(body.getString("preConsumeId")).isEqualTo("pc-1");
        assertThat(body.getString("requestId")).isEqualTo("req-1");
        assertThat(body.getLong("ownerPartyId")).isEqualTo(7L);
        assertThat(body.getIntValue("actualPromptTokens")).isEqualTo(100);
        assertThat(body.getIntValue("actualCompletionTokens")).isEqualTo(20);
        assertThat(body.getIntValue("cacheReadTokens")).isEqualTo(30);
        assertThat(body.getIntValue("cacheCreationTokens")).isEqualTo(5);
        assertThat(body.getLong("reasoningTokens")).isEqualTo(8L);
        assertThat(body.getLong("audioTokens")).isEqualTo(9L);
        assertThat(body.getIntValue("responseTimeMs")).isEqualTo(1234);
        assertThat(body.getBooleanValue("success")).isTrue();
        JSONArray replayedAttempts = body.getJSONArray("attempts");
        assertThat(replayedAttempts).hasSize(1);
        JSONObject detail = replayedAttempts.getJSONObject(0);
        assertThat(detail.getIntValue("sequence")).isEqualTo(1);
        assertThat(detail.getString("channelId")).isEqualTo("c1");
        assertThat(detail.getString("model")).isEqualTo("gpt-4o-mini");
        assertThat(detail.getString("errorClass")).isEqualTo("HTTP_502");
        assertThat(detail.getBooleanValue("billed")).isTrue();
        assertThat(detail.getIntValue("promptTokens")).isEqualTo(10);
        assertThat(detail.getIntValue("completionTokens")).isZero();
    }

    @Test
    @DisplayName("重放基础设施失败: retries+1 退避重排 (score = now + base*2^(n-1)), 不出队")
    void replayFailureReschedulesWithBackoff() {
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-2", "req-2", null, 10, 2, 0, 0, null, null, 100, null).withRetries(1);
        when(pendingStore.duePending(anyInt()))
                .thenReturn(Flux.just(entry(record)));
        backend.enqueue(new MockResponse().setResponseCode(500));

        // 重放走真实 HTTP (MockWebServer), 完成时刻不定: 窗口取 before + base*2 ~ +3s,
        // 足以区分错档 (base*1 ≈ +30s 差, base*4 ≈ +90s 差)
        long before = System.currentTimeMillis();
        job.reconcile();

        ArgumentCaptor<BillingPendingRecord> next = ArgumentCaptor.forClass(BillingPendingRecord.class);
        long base = new BillingReconcileProperties().getBaseBackoff().toMillis();
        verify(pendingStore, timeout(2000)).reschedule(eq(entry(record)), next.capture(),
                org.mockito.ArgumentMatchers.longThat(score ->
                        score >= before + base * 2 && score <= before + base * 2 + 3000));
        assertThat(next.getValue().retries()).isEqualTo(2);
        assertThat(next.getValue().preConsumeId()).isEqualTo("pc-2");
        assertThat(next.getValue().actualPromptTokens()).isEqualTo(10);
        verify(pendingStore, never()).remove(any());
    }

    @Test
    @DisplayName("重放超 max-attempts: 出队 + 死信单行 JSON 快照含全部结算参数")
    void exhaustedReplayGoesToDeadLetterWithFullSnapshot() {
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-3", "req-3", 7L, 100, 20, 30, 5, 8L, null, 1234, null).withRetries(4);
        when(pendingStore.duePending(anyInt()))
                .thenReturn(Flux.just(entry(record)));
        backend.enqueue(new MockResponse().setResponseCode(500));

        job.reconcile();

        verify(pendingStore, timeout(2000)).remove(entry(record));
        verify(pendingStore, never()).reschedule(any(), any(), anyLong());

        JSONObject payload = deadLetterPayload();
        assertThat(payload.getString("event")).isEqualTo("billing-dead-letter");
        assertThat(payload.getString("phase")).isEqualTo("reconcile");
        assertThat(payload.getString("reason")).contains("耗尽");
        JSONObject snapshot = payload.getJSONObject("record");
        assertThat(snapshot.getString("type")).isEqualTo("settle");
        assertThat(snapshot.getString("preConsumeId")).isEqualTo("pc-3");
        assertThat(snapshot.getString("requestId")).isEqualTo("req-3");
        assertThat(snapshot.getLong("ownerPartyId")).isEqualTo(7L);
        assertThat(snapshot.getIntValue("actualPromptTokens")).isEqualTo(100);
        assertThat(snapshot.getIntValue("actualCompletionTokens")).isEqualTo(20);
        assertThat(snapshot.getIntValue("cacheReadTokens")).isEqualTo(30);
        assertThat(snapshot.getIntValue("cacheCreationTokens")).isEqualTo(5);
        assertThat(snapshot.getLong("reasoningTokens")).isEqualTo(8L);
        assertThat(snapshot.getIntValue("responseTimeMs")).isEqualTo(1234);
        assertThat(snapshot.getIntValue("retries")).isEqualTo(4);
    }

    @Test
    @DisplayName("重放业务拒绝 (信封 code!=0): 同参数重试无意义 → 直接死信, 不重排")
    void businessRejectionDuringReplayGoesStraightToDeadLetter() {
        BillingPendingRecord record = BillingPendingRecord.forRefund("pc-4", "req-4", "client cancelled");
        when(pendingStore.duePending(anyInt()))
                .thenReturn(Flux.just(entry(record)));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10617,\"message\":\"用户算力余额不足\"}"));

        job.reconcile();

        verify(pendingStore, timeout(2000)).remove(entry(record));
        verify(pendingStore, never()).reschedule(any(), any(), anyLong());
        JSONObject payload = deadLetterPayload();
        assertThat(payload.getString("reason")).contains("10617");
        assertThat(payload.getJSONObject("record").getString("type")).isEqualTo("refund");
    }

    @Test
    @DisplayName("refund 重放成功: 请求体按原参重放 (preConsumeId/reason/requestId) → zrem")
    void refundReplaySendsOriginalParams() throws Exception {
        BillingPendingRecord record = BillingPendingRecord.forRefund("pc-5", "req-5", "client cancelled");
        when(pendingStore.duePending(anyInt()))
                .thenReturn(Flux.just(entry(record)));
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        job.reconcile();

        verify(pendingStore, timeout(2000)).remove(entry(record));
        RecordedRequest request = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        JSONObject body = JSON.parseObject(request.getBody().readUtf8());
        assertThat(body.getString("preConsumeId")).isEqualTo("pc-5");
        assertThat(body.getString("reason")).isEqualTo("client cancelled");
        assertThat(body.getString("requestId")).isEqualTo("req-5");
    }

    @Test
    @DisplayName("扫描源异常: 不抛出, 不触达重放")
    void scanErrorSwallowed() {
        when(pendingStore.duePending(anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("redis down")));
        assertThatCode(() -> job.reconcile()).doesNotThrowAnyException();
        verify(pendingStore, never()).remove(any());
    }

    @Test
    @DisplayName("issue #31 面归属: 配了 task.billing.path-prefix, 重放 settle 仍走通用前缀 (不串 task 面)")
    void replayStaysOnGenericPrefixWhenTaskPrefixConfigured() throws Exception {
        MockWebServer genericBackend = new MockWebServer();
        genericBackend.start();
        try {
            var legacy = new GatewayProperties();
            legacy.setUrl(genericBackend.url("/").toString().replaceAll("/$", ""));
            var spi = new TokenGatewayProperties();
            spi.getTask().getBilling().setPathPrefix("/v1/internal/billing/task");
            var genericApi = new HttpBillingApi(WebClient.builder(),
                    new CapabilityEndpoints(spi, legacy), new RpcInternalAuth(legacy));
            var isolatedStore = mock(BillingPendingStore.class);
            when(isolatedStore.remove(any())).thenReturn(Mono.just(1L));
            BillingPendingRecord record = BillingPendingRecord.forSettle(
                    "pc-31", "req-31", null, 10, 2, 0, 0, null, null, 100, null);
            when(isolatedStore.duePending(anyInt())).thenReturn(Flux.just(entry(record)));
            var isolatedJob = new BillingReconcileJob(isolatedStore, genericApi,
                    new BillingReconcileProperties());

            genericBackend.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"code\":0,\"data\":{\"creditConsumed\":0.5}}"));

            isolatedJob.reconcile();

            verify(isolatedStore, timeout(2000)).remove(any());
            RecordedRequest request = genericBackend.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            // task 前缀已配, 重放仍必须走通用前缀 (LLM 面契约不串 task 面)
            assertThat(request.getPath()).isEqualTo("/api/v1/internal/billing/settle");
        } finally {
            genericBackend.shutdown();
        }
    }
}
