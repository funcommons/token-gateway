package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.idempotency.IdempotencyStore;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.notify.TerminalEventHandler;
import fun.commons.tokengateway.task.notify.WebhookVerifier;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import fun.commons.tokengateway.thmp.ThmpSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
 * LotaskWebhookController 单测 (《05》§8: 验签直信 / 无签名回查 / Event-Id 去重 / 400 结构性错误).
 */
@DisplayName("LotaskWebhookController")
class LotaskWebhookControllerTest {

    private static final String SECRET = "tenant-secret-1";

    private TaskMetaStore metaStore;
    private LotaskTaskClient lotaskClient;
    private TerminalEventHandler terminalEventHandler;
    private LotaskWebhookController controller;

    @BeforeEach
    void setUp() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        props.getTask().getLotask().setTenantSecret(SECRET);
        metaStore = mock(TaskMetaStore.class);
        lotaskClient = mock(LotaskTaskClient.class);
        terminalEventHandler = mock(TerminalEventHandler.class);
        IdempotencyStore firstSeen = new IdempotencyStore() {
            @Override
            public Mono<Boolean> tryAcquire(String key, Duration ttl) {
                return Mono.just(true);
            }

            @Override
            public Mono<Void> release(String key) {
                return Mono.empty();
            }
        };
        controller = new LotaskWebhookController(new WebhookVerifier(props), firstSeen,
                metaStore, lotaskClient, terminalEventHandler);
        when(terminalEventHandler.onTerminal(anyString(), any(), anyString(), any()))
                .thenReturn(Mono.empty());
        when(terminalEventHandler.onTerminalView(anyString(), any(), any()))
                .thenReturn(Mono.empty());
    }

    private static String body(String id, String status) {
        return "{\"id\":\"" + id + "\",\"status\":\"" + status
                + "\",\"result\":{\"resources\":[\"https://up/v.mp4\"]}}";
    }

    private static String sig(String raw) {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        return ts + "|" + ThmpSignature.sign(SECRET, ts + "\n" + raw);
    }

    @Test
    @DisplayName("验签通过 → 直接按载荷处理 (不触 lotask 回查)")
    void verifiedDirectHandling() {
        String raw = body("lotask-1", "SUCCESS");
        String[] parts = sig(raw).split("\\|");
        when(metaStore.findTaskNo("lotask-1")).thenReturn(Mono.just("T1"));
        when(metaStore.getMeta("T1")).thenReturn(Mono.just(
                new TaskMeta("lotask-1", "pc1", "video", null, 0L)));

        StepVerifier.create(controller.receive("evt-1", parts[0], parts[1], raw))
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
                    assertThat(resp.getBody()).containsEntry("mode", "verified");
                })
                .verifyComplete();
        verify(terminalEventHandler).onTerminal(eq("T1"), any(), eq("SUCCESS"), any());
        verify(lotaskClient, never()).get(anyString());
    }

    @Test
    @DisplayName("无签名 → 不拒收, verify-then-act 回查平台核实后处理")
    void unsignedVerifyThenAct() {
        String raw = body("lotask-2", "FAILED");
        when(lotaskClient.get("lotask-2")).thenReturn(Mono.just(
                new LotaskTaskView("lotask-2", "FAILED", null, "UPSTREAM_ERROR", "boom")));
        when(metaStore.findTaskNo("lotask-2")).thenReturn(Mono.just("T2"));
        when(metaStore.getMeta("T2")).thenReturn(Mono.just(
                new TaskMeta("lotask-2", "pc2", "video", null, 0L)));

        StepVerifier.create(controller.receive("evt-2", null, null, raw))
                .assertNext(resp -> assertThat(resp.getBody()).containsEntry("mode", "reconciled"))
                .verifyComplete();
        verify(lotaskClient).get("lotask-2");
        verify(terminalEventHandler).onTerminalView(eq("T2"), any(), any());
    }

    @Test
    @DisplayName("Event-Id 重投 → 幂等跳过不处理")
    void duplicateEventSkipped() {
        IdempotencyStore seen = new IdempotencyStore() {
            @Override
            public Mono<Boolean> tryAcquire(String key, Duration ttl) {
                return Mono.just(false);
            }

            @Override
            public Mono<Void> release(String key) {
                return Mono.empty();
            }
        };
        TokenGatewayProperties props = new TokenGatewayProperties();
        LotaskWebhookController dup = new LotaskWebhookController(
                new WebhookVerifier(props), seen, metaStore, lotaskClient, terminalEventHandler);

        StepVerifier.create(dup.receive("evt-dup", null, null, body("lotask-3", "SUCCESS")))
                .assertNext(resp -> assertThat(resp.getBody()).containsEntry("mode", "duplicate"))
                .verifyComplete();
        verify(terminalEventHandler, never()).onTerminal(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("载荷缺 id/status → 400 (结构性错误, 平台不重投)")
    void malformedPayload400() {
        StepVerifier.create(controller.receive(null, null, null, "{\"foo\":1}"))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(400))
                .verifyComplete();
        StepVerifier.create(controller.receive(null, null, null, "not-json"))
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(400))
                .verifyComplete();
    }
}
