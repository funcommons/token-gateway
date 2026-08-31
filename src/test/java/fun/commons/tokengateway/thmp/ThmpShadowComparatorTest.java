package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.framework.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThmpShadowComparator 判定测试 (W2 影子归零清单的判定口径钉死).
 */
@DisplayName("ThmpShadowComparator (影子比对)")
class ThmpShadowComparatorTest {

    private static ThmpContractClient.ResolveResult result(ThmpContractClient.Candidate... candidates) {
        return new ThmpContractClient.ResolveResult(List.of(candidates), List.of(), false, false);
    }

    private static ThmpContractClient.Candidate candidate(String base, String protocol) {
        return new ThmpContractClient.Candidate("11", "5", 1, base, protocol, "ROUND_ROBIN",
                List.of(), null, null, null);
    }

    private static DistributeVO oldRoute(String base, String protocol) {
        return DistributeVO.builder().channelId("99").baseUrl(base).protocol(protocol).build();
    }

    private StubClient stub;
    private ThmpShadowComparator comparator;

    @BeforeEach
    void setUp() {
        stub = new ThmpShadowComparatorTest.StubClient();
        comparator = new ThmpShadowComparator(
                new ThmpCandidateCache(stub, Duration.ofSeconds(30), Schedulers.immediate()));
    }

    private static class StubClient extends ThmpContractClient {
        ThmpContractClient.ResolveResult next;
        RuntimeException error;
        int calls;

        StubClient() {
            super(null, null);
        }

        @Override
        public Mono<ApiResponse<ThmpContractClient.ResolveResult>> resolve(String modelCode,
                                                                           String tenantId) {
            calls++;
            if (error != null) {
                return Mono.error(error);
            }
            return Mono.just(ApiResponse.success(next));
        }
    }

    @Test
    @DisplayName("baseUrl 命中 + protocol 一致 → MATCH")
    void matchOnBaseUrlAndProtocol() {
        stub.next = result(candidate("https://up.internal/v1", "openai"));

        var verdict = comparator.verdict("gpt-4o", "0", oldRoute("https://up.internal/v1", "openai"))
                .block();

        assertThat(verdict.result()).isEqualTo("MATCH");
        assertThat(verdict.detail()).isNull();
        assertThat(verdict.thmpBaseUrls()).containsExactly("https://up.internal/v1");
    }

    @Test
    @DisplayName("baseUrl 未命中候选集 → DIFF [base_url]")
    void diffWhenBaseUrlMiss() {
        stub.next = result(candidate("https://other/v1", "openai"));

        var verdict = comparator.verdict("gpt-4o", "0", oldRoute("https://up.internal/v1", "openai"))
                .block();

        assertThat(verdict.result()).isEqualTo("DIFF");
        assertThat(verdict.detail()).contains("base_url");
    }

    @Test
    @DisplayName("baseUrl 命中但 protocol 不一致 → DIFF [protocol] (大小写不敏感)")
    void diffOnProtocolMismatch() {
        stub.next = result(candidate("https://up.internal/v1", "OpenAI"));

        var verdict = comparator.verdict("gpt-4o", "0", oldRoute("https://up.internal/v1", "anthropic"))
                .block();

        assertThat(verdict.result()).isEqualTo("DIFF");
        assertThat(verdict.detail()).contains("protocol:anthropic!=openai");
    }

    @Test
    @DisplayName("中台无候选 → THMP_EMPTY")
    void emptyWhenNoCandidates() {
        stub.next = new ThmpContractClient.ResolveResult(List.of(), List.of(), false, false);

        var verdict = comparator.verdict("gpt-4o", "0", oldRoute("https://up.internal/v1", "openai"))
                .block();

        assertThat(verdict.result()).isEqualTo("THMP_EMPTY");
    }

    @Test
    @DisplayName("中台调用失败 → THMP_ERROR (吞掉不抛)")
    void errorWhenThmpFails() {
        stub.error = new IllegalStateException("timeout");

        var verdict = comparator.verdict("gpt-4o", "0", oldRoute("https://up.internal/v1", "openai"))
                .block();

        assertThat(verdict.result()).isEqualTo("THMP_ERROR");
        assertThat(verdict.detail()).contains("timeout");
    }

    @Test
    @DisplayName("非数字旧租户归 \"0\" (旧世界租户无 THMP ID 映射)")
    void nonNumericTenantNormalizedToZero() {
        stub.next = result(candidate("https://up.internal/v1", "openai"));

        comparator.verdict("gpt-4o", "tn-legacy-77", oldRoute("https://up.internal/v1", "openai"))
                .block();

        var verdict = comparator.verdict("gpt-4o", "tn-legacy-77", oldRoute("https://up.internal/v1", "openai"))
                .block();
        assertThat(verdict.tenantId()).isEqualTo("0");
        // 归一后同 key → SWR 缓存命中, 只打一次远端
        assertThat(stub.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("compare: fire-and-forget — 不抛异常且异步落判定")
    void compareIsFireAndForget() throws Exception {
        stub.next = result(candidate("https://up.internal/v1", "openai"));

        comparator.compare("gpt-4o", "0", oldRoute("https://up.internal/v1", "openai"));

        // compare 不返回 Mono, 主线程立即返回; 等 reactor 异步完成
        Thread.sleep(300);
        assertThat(stub.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("compare: null/空 model 与 null oldRoute 直接跳过")
    void compareSkipsInvalidInput() {
        comparator.compare(null, "0", oldRoute("https://a", "openai"));
        comparator.compare("  ", "0", oldRoute("https://a", "openai"));
        comparator.compare("gpt-4o", "0", null);
        assertThat(stub.calls).isEqualTo(0);
    }

    @Test
    @DisplayName("logLine: 结构化单行含 result/model/old_base/thmp_bases")
    void logLineFormat() {
        stub.next = result(candidate("https://up.internal/v1", "openai"));

        var verdict = comparator.verdict("gpt-4o", "0", oldRoute("https://up.internal/v1", "openai"))
                .block();

        assertThat(verdict.logLine())
                .startsWith("result=MATCH")
                .contains("model=gpt-4o")
                .contains("old_channel=99")
                .contains("thmp_bases=[https://up.internal/v1]");
    }
}
