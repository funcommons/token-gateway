package fun.commons.tokengateway.moderation;

import fun.commons.tokengateway.rpc.HttpModerationApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ModerationGate 单测 (webflux 版).
 *
 * <p>验证 ScanRequest 字段映射 + 返回值原样透传 (不重复做 BLOCK→RelayException 转换, 该转换在 RelayOrchestrator).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModerationGate scanInput")
class ModerationGateTest {

    @Mock
    private HttpModerationApi httpModerationApi;

    private ModerationGate gate;

    @BeforeEach
    void setUp() {
        gate = new ModerationGate(httpModerationApi);
    }

    @Test
    @DisplayName("scan 返回 PASS_THROUGH → 透传")
    void passThrough() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.pass("原文")));

        StepVerifier.create(gate.scanInput("100", "200", "用户输入", null))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.PASS_THROUGH);
                    assertThat(outcome.sanitizedContent()).isEqualTo("原文");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("scan 返回 BLOCK_REQUEST → 透传 (RelayOrchestrator 负责抛 400)")
    void block() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.block(List.of("sensitive_word"))));

        StepVerifier.create(gate.scanInput("100", "200", "违规输入", null))
                .assertNext(outcome -> {
                    assertThat(outcome.isBlocked()).isTrue();
                    assertThat(outcome.ruleCodes()).containsExactly("sensitive_word");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("scan 返回 MASK_CONTENT → 透传")
    void mask() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.mask("<脱敏>")));

        StepVerifier.create(gate.scanInput("100", "200", "电话 13800138000", null))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.MASK_CONTENT);
                    assertThat(outcome.sanitizedContent()).isEqualTo("<脱敏>");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("prompt=null → content=空串 (避免主应用 NPE)")
    void nullPromptNormalizedToEmpty() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.pass(null)));

        StepVerifier.create(gate.scanInput("100", "200", null, null))
                .assertNext(outcome -> assertThat(outcome.action())
                        .isEqualTo(ModerationOutcome.Action.PASS_THROUGH))
                .verifyComplete();

        ArgumentCaptor<ScanRequest> cap = ArgumentCaptor.forClass(ScanRequest.class);
        verify(httpModerationApi).scan(cap.capture());
        assertThat(cap.getValue().getContent()).isEmpty();
    }

    @Test
    @DisplayName("systemPrompt 透传 (当前主应用未消费, 留扩展)")
    void systemPromptForwarded() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.pass("x")));

        gate.scanInput("100", "200", "u", "你是一个助手").block();

        ArgumentCaptor<ScanRequest> cap = ArgumentCaptor.forClass(ScanRequest.class);
        verify(httpModerationApi).scan(cap.capture());
        assertThat(cap.getValue().getSystemPrompt()).isEqualTo("你是一个助手");
    }

    @Test
    @DisplayName("tenantId/userId/direction 透传")
    void fieldsForwarded() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.pass("x")));

        gate.scanInput("tn-1", "u-2", "content", null).block();

        ArgumentCaptor<ScanRequest> cap = ArgumentCaptor.forClass(ScanRequest.class);
        verify(httpModerationApi).scan(cap.capture());
        assertThat(cap.getValue().getTenantId()).isEqualTo("tn-1");
        assertThat(cap.getValue().getUserId()).isEqualTo("u-2");
        assertThat(cap.getValue().getDirection()).isEqualTo("INPUT");
        assertThat(cap.getValue().getContent()).isEqualTo("content");
    }

    @Test
    @DisplayName("HttpModerationApi 返回 fail-open PASS_THROUGH → 透传 (不阻断主流程)")
    void rpcFailOpenPropagated() {
        when(httpModerationApi.scan(any()))
                .thenReturn(Mono.just(ModerationOutcome.pass("原文")));

        StepVerifier.create(gate.scanInput("100", "200", "u", null))
                .assertNext(outcome -> {
                    assertThat(outcome.action()).isEqualTo(ModerationOutcome.Action.PASS_THROUGH);
                    assertThat(outcome.isBlocked()).isFalse();
                })
                .verifyComplete();
    }
}
