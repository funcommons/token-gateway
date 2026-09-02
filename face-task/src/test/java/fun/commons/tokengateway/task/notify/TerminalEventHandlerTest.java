package fun.commons.tokengateway.task.notify;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.billing.TaskBillingSaga;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.ResourceUrlConverter;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TerminalEventHandler 单测 (《05》§5.2: SUCCESS 资源转换 / FAILED 退款 / EXPIRED 超时钟).
 */
@DisplayName("TerminalEventHandler")
class TerminalEventHandlerTest {

    private TaskMetaStore metaStore;
    private TaskBillingSaga billingSaga;
    private NotifyDispatcher notifyDispatcher;
    private TerminalEventHandler handler;

    private static final TaskMeta META = new TaskMeta(
            "lotask-id-1", "pc1", "video", "https://caller/cb", 0L, null);

    @BeforeEach
    void setUp() {
        metaStore = mock(TaskMetaStore.class);
        billingSaga = mock(TaskBillingSaga.class);
        notifyDispatcher = mock(NotifyDispatcher.class);
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().setResourceSignKey("test-sign-key");
        when(billingSaga.refundOnce(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(billingSaga.settleOnce(anyString(), anyString())).thenReturn(Mono.empty());
        when(metaStore.saveTerminalResult(anyString(), anyString(), any())).thenReturn(Mono.empty());
        when(metaStore.clearDeadline(anyString())).thenReturn(Mono.empty());
        when(metaStore.closePending(anyString())).thenReturn(Mono.empty());
        handler = new TerminalEventHandler(metaStore, billingSaga, notifyDispatcher,
                new ResourceUrlConverter(new ResourceSigner(props)), props);
    }

    @Test
    @DisplayName("SUCCESS: resources 转 sig 代理 URL 落终态存储, 不退款, notify")
    void successConvertsResources() {
        Map<String, Object> result = Map.of(
                "resources", List.of("https://upstream/raw.mp4"),
                "usage", Map.of("seconds", 5));

        StepVerifier.create(handler.onTerminal("T1", META, "SUCCESS", result))
                .verifyComplete();

        org.mockito.ArgumentCaptor<String> saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(metaStore).saveTerminalResult(eq("T1"), saved.capture(), any());
        String entry = saved.getValue();
        assertThat(entry).contains("\"status\":\"SUCCEEDED\"");
        assertThat(entry).contains("/v1/resources/T1/0?exp=");
        assertThat(entry).doesNotContain("https://upstream/raw.mp4");
        verify(billingSaga, never()).refundOnce(anyString(), anyString(), anyString());
        verify(metaStore).clearDeadline("T1");
        verify(metaStore).closePending("T1");
        verify(notifyDispatcher).dispatch(eq("T1"), eq("https://caller/cb"), any());
    }

    @Test
    @DisplayName("FAILED: 全额退款 (幂等键 preConsumeId) → 落终态条目 → notify (先退款后通知)")
    void failedRefunds() {
        StepVerifier.create(handler.onTerminal("T1", META, "FAILED", null))
                .verifyComplete();
        verify(billingSaga).refundOnce("pc1", "task FAILED", "T1");
        verify(notifyDispatcher).dispatch(eq("T1"), eq("https://caller/cb"), any());
    }

    @Test
    @DisplayName("EXPIRED (超时钟): 退款 + 终态条目 status=EXPIRED + notify")
    void expiredViaTimeoutClock() {
        StepVerifier.create(handler.onExpired("T1", META)).verifyComplete();
        verify(billingSaga).refundOnce(eq("pc1"), contains("EXPIRED"), eq("T1"));
        org.mockito.ArgumentCaptor<String> saved = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(metaStore).saveTerminalResult(eq("T1"), saved.capture(), any());
        assertThat(saved.getValue()).contains("\"status\":\"EXPIRED\"");
        assertThat(saved.getValue()).contains("TIMEOUT");
    }

    @Test
    @DisplayName("非终态 (RUNNING) → 静默忽略")
    void nonTerminalIgnored() {
        StepVerifier.create(handler.onTerminal("T1", META, "RUNNING", null)).verifyComplete();
        verify(billingSaga, never()).refundOnce(anyString(), anyString(), anyString());
        verify(notifyDispatcher, never()).dispatch(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("元数据缺失 → 告警不动作 (等对账兜底)")
    void missingMetaNoop() {
        StepVerifier.create(handler.onTerminal("T1", null, "FAILED", null)).verifyComplete();
        verify(billingSaga, never()).refundOnce(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("CANCELLED 映射 FAILED: 运营取消全额退款")
    void cancelledRefunds() {
        StepVerifier.create(handler.onTerminal("T1", META, "CANCELLED", null)).verifyComplete();
        verify(billingSaga).refundOnce("pc1", "task FAILED", "T1");
    }
}
