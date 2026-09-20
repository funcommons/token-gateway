package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.config.ErrorContractProperties;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.moderation.ModerationGate;
import fun.commons.tokengateway.moderation.ModerationOutcome;
import fun.commons.tokengateway.rpc.AdapterSelector;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpCutover;
import fun.commons.tokengateway.thmp.ThmpShadow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RelayOrchestrator 错误契约透传单测 (issue #24): distribute/preConsume 失败分支的能力面
 * 原码命中 gateway.error-passthrough-codes → RelayException(透传状态, 原码, 原始 message);
 * 未命中走既有默认映射 (502+10004 / 404+10400 / 402+10617) 不受白名单影响; 白名单优先级
 * (十参构造自定义契约压过默认映射, 九参构造默认白名单生效).
 *
 * <p>与 {@link RelayOrchestratorPassthroughTest} (MockWebServer 全链路) 互补: 本类用
 * Mockito 直接 mock tokenApi/channelApi/billingApi/moderationGate, 聚焦分支判定本身.
 */
@DisplayName("RelayOrchestrator 错误契约透传 (issue #24)")
class RelayOrchestratorErrorPassthroughTest {

    private HttpTokenApi tokenApi;
    private HttpChannelApi channelApi;
    private HttpBillingApi billingApi;
    private ModerationGate moderationGate;

    @BeforeEach
    void setUp() {
        tokenApi = mock(HttpTokenApi.class);
        channelApi = mock(HttpChannelApi.class);
        billingApi = mock(HttpBillingApi.class);
        moderationGate = mock(ModerationGate.class);
        // 前置公共链: token 有效 + moderation 放行 (不触真实 RPC)
        when(tokenApi.validate(any())).thenReturn(Mono.just(ApiResponse.success(
                TokenValidateVO.builder().valid(true).tokenId("1").userId("2")
                        .tenantId("3").groupId("g1").build())));
        when(moderationGate.scanInput(any(), any(), any(), any()))
                .thenReturn(Mono.just(ModerationOutcome.pass(null)));
    }

    /** 九参构造 (contract=null → 默认白名单) / 十参构造 (自定义契约). */
    private RelayOrchestrator orchestrator(ErrorContractProperties contract) {
        var adapterSelector = new AdapterSelector(new TokenGatewayProperties());
        if (contract == null) {
            return new RelayOrchestrator(tokenApi, channelApi, billingApi, moderationGate,
                    new ThmpShadow.Noop(), new ThmpCutover.Noop(),
                    adapterSelector, null, null);
        }
        return new RelayOrchestrator(tokenApi, channelApi, billingApi, moderationGate,
                new ThmpShadow.Noop(), new ThmpCutover.Noop(),
                adapterSelector, null, null, contract);
    }

    private void distributeFails(int code, String message) {
        when(channelApi.distribute(any()))
                .thenReturn(Mono.just(ApiResponse.<DistributeVO>fail(code, message)));
    }

    private void distributeSucceeds() {
        when(channelApi.distribute(any())).thenReturn(Mono.just(ApiResponse.success(
                DistributeVO.builder().channelId("c1").baseUrl("http://upstream")
                        .apiKey("sk-up").protocol("openai").build())));
    }

    private void preConsumeFails(int code, String message) {
        when(billingApi.preConsume(any()))
                .thenReturn(Mono.just(ApiResponse.<PreConsumeVO>fail(code, message)));
    }

    // ---- distribute 分支: 白名单命中透传 ----

    @Test
    @DisplayName("distribute code=4090 (默认白名单) → HTTP 403 + 原码 4090")
    void distribute4090Passes403() {
        distributeFails(4090, "风控拒绝");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 403 && re.getCode() == 4090);
    }

    @Test
    @DisplayName("distribute code=10601 → HTTP 402 + 原码 10601 (能力面余额口径)")
    void distribute10601Passes402() {
        distributeFails(10601, "余额不足");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402 && re.getCode() == 10601);
    }

    @Test
    @DisplayName("distribute code=10402 → HTTP 409 + 原码 10402 (状态冲突)")
    void distribute10402Passes409() {
        distributeFails(10402, "状态冲突");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 409 && re.getCode() == 10402);
    }

    // ---- distribute 分支: 未命中白名单 → 既有默认映射不变 ----

    @Test
    @DisplayName("distribute code=99999 未命中 → 既有 502 + 10004 (原码仅 message 残存)")
    void distributeUnknownCodeStill502() {
        distributeFails(99999, "weird failure");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 502 && re.getCode() == 10004
                        && re.getMessage().contains("weird failure"));
    }

    @Test
    @DisplayName("distribute code=10400 不在默认白名单 → 既有 404 + 10400 映射不变")
    void distribute10400Unchanged() {
        distributeFails(10400, "no channel");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404 && re.getCode() == 10400);
    }

    @Test
    @DisplayName("distribute code=20103 (bootstrap 模型不存在) → 既有 404 + 网关码 10400, 不受白名单影响")
    void distribute20103Still404() {
        distributeFails(20103, "model not found");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 404 && re.getCode() == 10400
                        && re.getMessage().contains("模型不存在或无可用渠道"));
    }

    // ---- preConsume 分支: 白名单短路 + 既有 10617 映射不变 ----

    @Test
    @DisplayName("preConsume code=10601 → 白名单短路 HTTP 402 + 原码 10601")
    void preConsume10601Passes402() {
        distributeSucceeds();
        preConsumeFails(10601, "余额不足");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402 && re.getCode() == 10601);
    }

    @Test
    @DisplayName("preConsume code=10617 (不在默认白名单) → 既有 402 + 10617 不变")
    void preConsume10617Unchanged() {
        distributeSucceeds();
        preConsumeFails(10617, "用户算力余额不足");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 402 && re.getCode() == 10617);
    }

    // ---- 白名单优先级 + message 保真 ----

    @Test
    @DisplayName("白名单压过默认映射: 十参构造覆盖 10400→409 → distribute 10400 得 409 而非 404")
    void customWhitelistOverridesDefaultMapping() {
        var contract = new ErrorContractProperties();
        contract.getErrorPassthroughCodes().put(10400, 409);
        distributeFails(10400, "no channel");
        StepVerifier.create(orchestrator(contract)
                        .prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 409 && re.getCode() == 10400);
    }

    @Test
    @DisplayName("九参构造默认白名单生效: distribute 4090 → 403 + 4090")
    void nineArgCtorUsesDefaultWhitelist() {
        distributeFails(4090, "风控拒绝");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && re.getHttpStatus() == 403 && re.getCode() == 4090);
    }

    @Test
    @DisplayName("透传 message = 能力面原始 message (原样, 不加前后缀)")
    void passthroughMessageIsRaw() {
        distributeFails(4090, "风控拒绝-原始文案");
        StepVerifier.create(orchestrator(null).prepare("sk", "gpt-4o", 10, 20, null, "req-1", null))
                .verifyErrorMatches(e -> e instanceof RelayException re
                        && "风控拒绝-原始文案".equals(re.getMessage()));
    }
}
