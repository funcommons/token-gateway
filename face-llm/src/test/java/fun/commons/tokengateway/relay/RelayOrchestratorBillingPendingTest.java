package fun.commons.tokengateway.relay;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.relay.RelayOrchestrator.PreparedRequest;
import fun.commons.tokengateway.relay.billing.BillingDeadLetters;
import fun.commons.tokengateway.relay.billing.BillingPendingRecord;
import fun.commons.tokengateway.relay.billing.BillingPendingStore;
import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RelayOrchestrator settle/refund 失败兜底接线单测 (issue #23):
 * 基础设施失败 (5xx 折叠 10003) → 入待重放队列; 业务信封 code!=0 → 不入队仅死信;
 * 兼容装配 (store 缺省) → 死信快照兜底.
 */
@DisplayName("RelayOrchestrator settle/refund 失败兜底")
class RelayOrchestratorBillingPendingTest {

    private MockWebServer backend;
    private BillingPendingStore pendingStore;
    private RelayOrchestrator orchestrator;
    private ListAppender<ILoggingEvent> deadLetterLog;
    private Logger deadLetterLogger;

    private static final PreparedRequest PREPARED =
            new PreparedRequest(null, null, "pc-1", "req-1", null, null);

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
        when(pendingStore.enqueue(any())).thenReturn(Mono.just(true));
        // 九参兼容构造 (错误契约走默认白名单; Spring 生产装配为十参注入
        // ErrorContractProperties); settle/refund 链不触达
        // moderationGate/thmp/adapter/tokenRoute, null 安全
        orchestrator = new RelayOrchestrator(null, null, billingApi, null,
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop(),
                null, null, pendingStore);

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

    private JSONObject deadLetterPayload() {
        List<ILoggingEvent> events = deadLetterLog.list;
        assertThat(events).as("死信日志应已落一条").isNotEmpty();
        String formatted = events.get(events.size() - 1).getFormattedMessage();
        assertThat(formatted).startsWith("[Billing-DeadLetter] ");
        return JSON.parseObject(formatted.substring("[Billing-DeadLetter] ".length()));
    }

    @Test
    @DisplayName("settle 基础设施失败 (5xx): 入待重放队列, 快照含全部结算参数; 返回值仍 ZERO 不影响主链")
    void settleInfraFailureEnqueuesPending() {
        backend.enqueue(new MockResponse().setResponseCode(500));
        List<SettleRequest.AttemptDetail> attempts = List.of(SettleRequest.AttemptDetail.builder()
                .sequence(1).channelId("c1").model("gpt-4o")
                .errorClass("HTTP_500").billed(true)
                .promptTokens(7).completionTokens(0)
                .build());

        StepVerifier.create(orchestrator.settle(PREPARED, 100, 20, 30, 1234, attempts))
                .expectNext(BigDecimal.ZERO)
                .verifyComplete();

        ArgumentCaptor<BillingPendingRecord> record = ArgumentCaptor.forClass(BillingPendingRecord.class);
        verify(pendingStore).enqueue(record.capture());
        BillingPendingRecord snapshot = record.getValue();
        assertThat(snapshot.type()).isEqualTo("settle");
        assertThat(snapshot.preConsumeId()).isEqualTo("pc-1");
        assertThat(snapshot.requestId()).isEqualTo("req-1");
        assertThat(snapshot.actualPromptTokens()).isEqualTo(100);
        assertThat(snapshot.actualCompletionTokens()).isEqualTo(20);
        assertThat(snapshot.cacheReadTokens()).isEqualTo(30);
        assertThat(snapshot.cacheCreationTokens()).isZero();
        assertThat(snapshot.responseTimeMs()).isEqualTo(1234);
        assertThat(snapshot.attempts()).hasSize(1);
        assertThat(snapshot.attempts().get(0).getChannelId()).isEqualTo("c1");
        assertThat(snapshot.retries()).isZero();
        assertThat(deadLetterLog.list).isEmpty();
    }

    @Test
    @DisplayName("settle 业务信封 code!=0: 不入队, 死信快照一次 (重试同参数无意义)")
    void settleBusinessRejectionDeadLettersWithoutEnqueue() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10617,\"message\":\"用户算力余额不足\"}"));

        StepVerifier.create(orchestrator.settle(PREPARED, 100, 20, 30, 1234, null))
                .expectNext(BigDecimal.ZERO)
                .verifyComplete();

        verify(pendingStore, never()).enqueue(any());
        JSONObject payload = deadLetterPayload();
        assertThat(payload.getString("phase")).isEqualTo("settle");
        assertThat(payload.getString("reason")).contains("10617");
        JSONObject snapshot = payload.getJSONObject("record");
        assertThat(snapshot.getString("preConsumeId")).isEqualTo("pc-1");
        assertThat(snapshot.getString("requestId")).isEqualTo("req-1");
        assertThat(snapshot.getIntValue("actualPromptTokens")).isEqualTo(100);
        assertThat(snapshot.getIntValue("actualCompletionTokens")).isEqualTo(20);
    }

    @Test
    @DisplayName("refund 基础设施失败 (5xx): 入待重放队列, 快照含退款原因")
    void refundInfraFailureEnqueuesPending() {
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(orchestrator.refund(PREPARED, "client cancelled"))
                .verifyComplete();

        ArgumentCaptor<BillingPendingRecord> record = ArgumentCaptor.forClass(BillingPendingRecord.class);
        verify(pendingStore).enqueue(record.capture());
        BillingPendingRecord snapshot = record.getValue();
        assertThat(snapshot.type()).isEqualTo("refund");
        assertThat(snapshot.isRefund()).isTrue();
        assertThat(snapshot.preConsumeId()).isEqualTo("pc-1");
        assertThat(snapshot.requestId()).isEqualTo("req-1");
        assertThat(snapshot.refundReason()).isEqualTo("client cancelled");
        assertThat(snapshot.retries()).isZero();
        assertThat(deadLetterLog.list).isEmpty();
    }

    @Test
    @DisplayName("refund 业务信封 code!=0: 不入队, 死信快照一次")
    void refundBusinessRejectionDeadLettersWithoutEnqueue() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"预扣单不存在\"}"));

        StepVerifier.create(orchestrator.refund(PREPARED, "upstream failed"))
                .verifyComplete();

        verify(pendingStore, never()).enqueue(any());
        JSONObject payload = deadLetterPayload();
        assertThat(payload.getString("phase")).isEqualTo("refund");
        assertThat(payload.getString("reason")).contains("10400");
        assertThat(payload.getJSONObject("record").getString("refundReason"))
                .isEqualTo("upstream failed");
    }

    @Test
    @DisplayName("兼容装配 (九参传 null store / 六参构造): 基础设施失败仅死信快照, 不触队列")
    void missingStoreFallsBackToDeadLetterLog() {
        var props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        var billingApi = new HttpBillingApi(WebClient.builder(),
                new CapabilityEndpoints(new TokenGatewayProperties(), props),
                new RpcInternalAuth(props));
        // 八参兼容构造 → store=null
        RelayOrchestrator legacy = new RelayOrchestrator(null, null, billingApi, null,
                new fun.commons.tokengateway.thmp.ThmpShadow.Noop(),
                new fun.commons.tokengateway.thmp.ThmpCutover.Noop());
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(legacy.settle(PREPARED, 10, 2, 0, 100, null))
                .expectNext(BigDecimal.ZERO)
                .verifyComplete();

        verify(pendingStore, never()).enqueue(any());
        JSONObject payload = deadLetterPayload();
        assertThat(payload.getString("reason")).contains("待重放队列未装配");
        assertThat(payload.getJSONObject("record").getString("preConsumeId")).isEqualTo("pc-1");
    }
}
