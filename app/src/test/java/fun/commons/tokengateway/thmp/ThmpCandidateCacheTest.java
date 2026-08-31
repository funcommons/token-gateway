package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.framework.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThmpCandidateCache SWR 语义测试 (测试注入 immediate 调度器 → 后台刷新同步化, 确定性断言).
 */
@DisplayName("ThmpCandidateCache (SWR)")
class ThmpCandidateCacheTest {

    /** 假客户端: 计数并按脚本应答 */
    private static class StubClient extends ThmpContractClient {
        final List<ThmpContractClient.ResolveResult> script = new ArrayList<>();
        final List<RuntimeException> errors = new ArrayList<>();
        int calls;

        StubClient() {
            super(null, null);
        }

        @Override
        public Mono<ApiResponse<ThmpContractClient.ResolveResult>> resolve(String modelCode,
                                                                           String tenantId) {
            int idx = Math.min(calls, script.size() - 1);
            calls++;
            if (!errors.isEmpty()) {
                RuntimeException e = errors.remove(0);
                return Mono.error(e);
            }
            return Mono.just(ApiResponse.success(script.get(idx)));
        }

        static ThmpContractClient.ResolveResult result(String base) {
            return new ThmpContractClient.ResolveResult(
                    List.of(new ThmpContractClient.Candidate("1", "1", 1, base, "openai",
                            "ROUND_ROBIN", List.of(), null, null, null)),
                    List.of(), false, false);
        }
    }

    private StubClient stub;
    private ThmpCandidateCache cache;

    @BeforeEach
    void setUp() {
        stub = new StubClient();
        cache = new ThmpCandidateCache(stub, Duration.ofMillis(50), Duration.ofMillis(60),
                Schedulers.immediate(), ThmpCandidateCache.MAX_ENTRIES);
    }

    @Test
    @DisplayName("miss → 阻塞拉取并回填; 二次读取命中缓存不再发请求")
    void missFetchesThenCached() {
        stub.script.add(StubClient.result("https://a"));

        var first = cache.get("gpt-4o", "0").block();
        var second = cache.get("gpt-4o", "0").block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(stub.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("过期 → 先回旧值, 后台刷新拉新值 (SWR 核心)")
    void staleReturnsOldThenRefreshes() throws Exception {
        stub.script.add(StubClient.result("https://old"));
        stub.script.add(StubClient.result("https://new"));

        assertThat(cache.get("m", "0").block().candidates().get(0).upstream_base_url())
                .isEqualTo("https://old");

        // 等缓存过期 (ttl 50ms)
        Thread.sleep(80);

        // 过期读: 回旧值 + 触发后台刷新 (immediate 调度器下刷新同步完成)
        var stale = cache.get("m", "0").block();
        assertThat(stale.candidates().get(0).upstream_base_url()).isEqualTo("https://old");
        assertThat(stub.calls).isEqualTo(2);

        // 再次读: 已是新值
        assertThat(cache.get("m", "0").block().candidates().get(0).upstream_base_url())
                .isEqualTo("https://new");
    }

    @Test
    @DisplayName("刷新进行中 → 单飞不重复发请求")
    void refreshSingleFlight() throws Exception {
        stub.script.add(StubClient.result("https://old"));
        cache.get("m", "0").block();
        Thread.sleep(80);

        // 并发触发两次过期读 → 后台刷新只发一次
        cache.get("m", "0").subscribe();
        cache.get("m", "0").subscribe();
        Thread.sleep(200);

        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("miss 拉取失败 → 透传错误 (影子期不做负缓存)")
    void missFailurePropagates() {
        stub.script.add(StubClient.result("https://a"));
        stub.errors.add(new IllegalStateException("thmp down"));

        var thrown = new Throwable[1];
        try {
            cache.get("m", "0").block(Duration.ofSeconds(2));
        } catch (Exception e) {
            thrown[0] = e;
        }
        assertThat(thrown[0]).isNotNull();
    }

    @Test
    @DisplayName("后台刷新失败 → 保旧值不炸")
    void refreshFailureKeepsStale() throws Exception {
        stub.script.add(StubClient.result("https://old"));
        cache.get("m", "0").block();
        Thread.sleep(80);
        stub.errors.add(new IllegalStateException("refresh fail"));

        var v = cache.get("m", "0").block();
        assertThat(v.candidates().get(0).upstream_base_url()).isEqualTo("https://old");
    }

    @Test
    @DisplayName("负缓存: miss 失败后短窗内 fail-fast (零远端调用) — THMP 故障不穿透")
    void negativeCacheFailsFast() {
        stub.script.add(StubClient.result("https://a"));
        stub.errors.add(new IllegalStateException("thmp down"));

        try {
            cache.get("m", "0").block(Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }
        long afterFirst = stub.calls;
        try {
            cache.get("m", "0").block(Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }
        // 第二次直接吃负缓存, 不再打远端
        assertThat(stub.calls).isEqualTo(afterFirst);
        assertThat(cache.negativeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("负缓存过期后重新拉取; 成功回填清负缓存")
    void negativeCacheExpiresThenRecovers() throws Exception {
        stub.script.add(StubClient.result("https://a"));
        stub.errors.add(new IllegalStateException("thmp down"));
        try {
            cache.get("m", "0").block(Duration.ofSeconds(2));
        } catch (Exception ignored) {
        }

        Thread.sleep(80);
        stub.errors.clear();
        var v = cache.get("m", "0").block();
        assertThat(v.candidates().get(0).upstream_base_url()).isEqualTo("https://a");
        assertThat(cache.get("m", "0").block()).isNotNull();
        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("LRU: 容量满淘汰最久未访问 (不再拒新 key); 被访问项保留")
    void lruEvictsLeastRecentlyUsed() {
        ThmpCandidateCache tiny = new ThmpCandidateCache(stub, Duration.ofSeconds(30),
                Duration.ofSeconds(15), Schedulers.immediate(), 3);
        stub.script.add(StubClient.result("https://x"));

        tiny.get("m1", "0").block();
        tiny.get("m2", "0").block();
        tiny.get("m3", "0").block();
        // 触摸 m1 → m2 变为最久未访问
        tiny.get("m1", "0").block();
        tiny.get("m4", "0").block();

        // m2 被淘汰 → 重新读取触发新 fetch; m1/m3/m4 仍命中零远端
        int before = stub.calls;
        tiny.get("m1", "0").block();
        tiny.get("m3", "0").block();
        tiny.get("m4", "0").block();
        assertThat(stub.calls).isEqualTo(before);
        tiny.get("m2", "0").block();
        assertThat(stub.calls).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("信封 code 非 0 → 视为拉取失败透传")
    void envelopeFailurePropagates() {
        ThmpCandidateCache failCache = new ThmpCandidateCache(
                new ThmpContractClient(null, null) {
                    @Override
                    public Mono<ApiResponse<ThmpContractClient.ResolveResult>> resolve(
                            String modelCode, String tenantId) {
                        return Mono.just(ApiResponse.fail(10400, "目录模型不存在"));
                    }
                },
                Duration.ofSeconds(30), Schedulers.immediate());

        var thrown = new Throwable[1];
        try {
            failCache.get("no-such", "0").block(Duration.ofSeconds(2));
        } catch (Exception e) {
            thrown[0] = e;
        }
        assertThat(thrown[0]).isNotNull();
        assertThat(String.valueOf(thrown[0].getMessage())).contains("10400");
    }
}
