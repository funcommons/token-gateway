package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.framework.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThmpCutoverRouter 测试: 灰度判定 (名单/百分比/确定性) + THMP 路由构建 + 失败回旧语义.
 */
@DisplayName("ThmpCutoverRouter (W3 灰度切流)")
class ThmpCutoverRouterTest {

    private static final String PASS = "thmp-dev-enc-passphrase-0000000000000001";

    private ThmpCutoverRouterTest.StubClient stub;
    private ThmpContractProperties props;

    @BeforeEach
    void setUp() {
        stub = new StubClient();
        props = new ThmpContractProperties();
        props.setEnabled(true);
        props.setCutoverModels(List.of("gpt-4o"));
        props.setCutoverPercent(100);
    }

    private static class StubClient extends ThmpContractClient {
        ThmpContractClient.ResolveResult next;
        RuntimeException error;

        StubClient() {
            super(null, null);
        }

        @Override
        public Mono<ApiResponse<ThmpContractClient.ResolveResult>> resolve(String modelCode,
                                                                           String tenantId) {
            if (error != null) {
                return Mono.error(error);
            }
            return Mono.just(ApiResponse.success(next));
        }
    }

    private ThmpCutoverRouter router() {
        ThmpKeyCipher cipher = new ThmpKeyCipher(Map.of(), PASS);
        ThmpCandidateCache cache = new ThmpCandidateCache(stub, Duration.ofSeconds(30),
                Schedulers.immediate());
        return new ThmpCutoverRouter(props, cache, cipher);
    }

    private static ThmpContractClient.ResolveResult result(ThmpContractClient.Candidate... candidates) {
        return new ThmpContractClient.ResolveResult(List.of(candidates), List.of(), false, false);
    }

    private ThmpContractClient.Candidate candidate(String keyCipher) {
        return new ThmpContractClient.Candidate("11", "5", 1, "https://up.internal/v1",
                "openai", "ROUND_ROBIN",
                List.of(new ThmpContractClient.KeyEntry("7", keyCipher)), null, null, null);
    }

    @Test
    @DisplayName("灰度判定: 名单外模型不切 / percent=0 不切 / percent=100 全切")
    void grayDecision() {
        ThmpCutoverRouter router = router();
        props.setCutoverPercent(100);
        assertThat(router.shouldCut("gpt-4o", "0", "req-1")).isTrue();
        assertThat(router.shouldCut("claude-x", "0", "req-1")).isFalse();

        props.setCutoverPercent(0);
        assertThat(router.shouldCut("gpt-4o", "0", "req-1")).isFalse();
    }

    @Test
    @DisplayName("灰度判定: 同输入确定性 — 百次同判定 (无 RNG)")
    void grayDeterministic() {
        props.setCutoverPercent(30);
        ThmpCutoverRouter router = router();
        for (int i = 0; i < 100; i++) {
            boolean first = router.shouldCut("gpt-4o", "0", "req-" + i);
            for (int j = 0; j < 5; j++) {
                assertThat(router.shouldCut("gpt-4o", "0", "req-" + i)).isEqualTo(first);
            }
        }
    }

    @Test
    @DisplayName("route: 候选+密钥 → DistributeVO (baseUrl/protocol/明文 key/PLATFORM)")
    void routeBuildsDistributeVO() {
        ThmpKeyCipher cipher = new ThmpKeyCipher(Map.of(), PASS);
        String cipherText = cipher.encrypt(0, "sk-plain-upstream");
        stub.next = result(candidate(cipherText));

        StepVerifier.create(router().route("gpt-4o", "0", "req-1"))
                .assertNext(vo -> {
                    assertThat(vo.getBaseUrl()).isEqualTo("https://up.internal/v1");
                    assertThat(vo.getProtocol()).isEqualTo("openai");
                    assertThat(vo.getApiKey()).isEqualTo("sk-plain-upstream");
                    assertThat(vo.getChannelId()).isEqualTo("5");
                    assertThat(vo.getOwnerType())
                            .isEqualTo(fun.commons.tokengateway.contract.OwnerType.PLATFORM);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("route: 密钥解不开 (跨口令) → 跳过候选 → 空 (回旧链路, 不炸主链)")
    void routeSkipsUndecryptable() {
        ThmpKeyCipher other = new ThmpKeyCipher(Map.of(), "other-passphrase-00000000000000000001");
        stub.next = result(candidate(other.encrypt(0, "byok-key")));

        StepVerifier.create(router().route("gpt-4o", "0", "req-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("route: 无候选 / resolve 失败 → 空 (回旧)")
    void routeEmptyOnNoCandidateOrError() {
        stub.next = new ThmpContractClient.ResolveResult(List.of(), List.of(), false, false);
        StepVerifier.create(router().route("gpt-4o", "0", "req-1")).verifyComplete();

        stub.error = new IllegalStateException("thmp down");
        StepVerifier.create(router().route("gpt-4o", "0", "req-1")).verifyComplete();
    }

    @Test
    @DisplayName("route: 名单外模型 → 直接空 (不触 THMP)")
    void routeSkipsNonListedModel() {
        StepVerifier.create(router().route("claude-x", "0", "req-1")).verifyComplete();
        assertThat(stub.next).isNull();
    }

    @Test
    @DisplayName("Noop: 未配置名单 → 恒空 (一键回旧语义 = 配置清空)")
    void noopWhenNoModels() {
        props.setCutoverModels(List.of());
        StepVerifier.create(router().route("gpt-4o", "0", "req-1")).verifyComplete();
        assertThat(router().shouldCut("gpt-4o", "0", "req-1")).isFalse();
    }
}
