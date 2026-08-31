package fun.commons.tokengateway.thmp;

import fun.commons.tokengateway.framework.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * THMP 候选 SWR 缓存 (stale-while-revalidate, 22 号 S2-W1; S2-W3 前加固).
 *
 * <p>语义:
 * <ul>
 *   <li>新鲜 (age &lt; ttl) → 直接回缓存, 零远端调用</li>
 *   <li>过期 → 先回旧值 + 后台异步刷新 (单飞: 同 key 刷新进行中不重复发起)</li>
 *   <li>miss → 阻塞拉取并回填; 失败 → <b>负缓存</b> (negative-ttl 窗内 fail-fast, THMP
 *       故障/永错不穿透 — 切流灰度期防 3s 超时 × 每请求的时延放大; 过期自动重试, 成功回填即清)</li>
 *   <li>容量 → <b>LRU 淘汰</b>最久未访问条目 (访问即触摸), 不再拒新 key</li>
 * </ul>
 *
 * <p>mc-cache §2/§5 对齐: 全键 TTL 过期 (无永生键); 负对象短 TTL 防穿透; 刷新失败保旧值
 * 不负缓存 (SWR 语义优先). 无 Redis 依赖 (进程内 — 影子/灰度期单实例数据面, Redis 化随
 * 多实例切流评估). 刷新调度器可注入 (生产 boundedElastic, 测试 immediate 确定性).
 */
@Slf4j
public class ThmpCandidateCache {

    /** 默认容量上限 */
    static final int MAX_ENTRIES = 500;

    /** 默认负缓存窗 (THMP 故障时 fail-fast, 短窗保恢复灵敏度) */
    static final java.time.Duration DEFAULT_NEGATIVE_TTL = java.time.Duration.ofSeconds(15);

    private final ThmpContractClient client;
    private final java.time.Duration ttl;
    private final java.time.Duration negativeTtl;
    private final Scheduler refreshScheduler;
    private final int maxEntries;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> refreshing = new ConcurrentHashMap<>();

    /** 观测: fetch/hit/negative 计数 (单测断言 + 影子/灰度期排障) */
    private final AtomicLong fetchCount = new AtomicLong();
    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong negativeCount = new AtomicLong();

    public ThmpCandidateCache(ThmpContractClient client, java.time.Duration ttl) {
        this(client, ttl, DEFAULT_NEGATIVE_TTL, Schedulers.boundedElastic(), MAX_ENTRIES);
    }

    /** 生产装配入口: ttl + 负缓存窗可配 */
    public ThmpCandidateCache(ThmpContractClient client, java.time.Duration ttl,
                              java.time.Duration negativeTtl) {
        this(client, ttl, negativeTtl, Schedulers.boundedElastic(), MAX_ENTRIES);
    }

    /** 测试入口: 注入确定性调度器 */
    ThmpCandidateCache(ThmpContractClient client, java.time.Duration ttl, Scheduler refreshScheduler) {
        this(client, ttl, DEFAULT_NEGATIVE_TTL, refreshScheduler, MAX_ENTRIES);
    }

    ThmpCandidateCache(ThmpContractClient client, java.time.Duration ttl,
                       java.time.Duration negativeTtl, Scheduler refreshScheduler, int maxEntries) {
        this.client = client;
        this.ttl = ttl;
        this.negativeTtl = negativeTtl;
        this.refreshScheduler = refreshScheduler;
        this.maxEntries = maxEntries;
    }

    public long fetchCount() {
        return fetchCount.get();
    }

    public long negativeCount() {
        return negativeCount.get();
    }

    public long hitCount() {
        return hitCount.get();
    }

    /**
     * 取候选: 新鲜回缓存 / 过期回旧值 + 后台刷新 / miss 阻塞拉取 (失败入负缓存).
     */
    public Mono<ThmpContractClient.ResolveResult> get(String modelCode, String tenantId) {
        String key = modelCode + "|" + tenantId;
        Entry hit = store.get(key);
        if (hit == null) {
            return fetchAndCache(key, modelCode, tenantId);
        }
        hit.touch();
        long age = System.nanoTime() - hit.storedAtNanos;
        if (hit.isNegative()) {
            if (age < negativeTtl.toNanos()) {
                negativeCount.incrementAndGet();
                return Mono.error(hit.error);
            }
            store.remove(key, hit);
            return fetchAndCache(key, modelCode, tenantId);
        }
        if (age < ttl.toNanos()) {
            hitCount.incrementAndGet();
            return Mono.just(hit.value);
        }
        triggerRefresh(key, modelCode, tenantId);
        return Mono.just(hit.value);
    }

    private Mono<ThmpContractClient.ResolveResult> fetchAndCache(String key, String modelCode,
                                                                 String tenantId) {
        return doFetch(modelCode, tenantId)
                .doOnNext(result -> putPositive(key, result))
                .onErrorResume(e -> {
                    putNegative(key, e);
                    return Mono.error(e);
                });
    }

    private void triggerRefresh(String key, String modelCode, String tenantId) {
        AtomicBoolean inFlight = refreshing.computeIfAbsent(key, k -> new AtomicBoolean());
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        // 刷新失败保旧值, 不入负缓存 (SWR: 旧值好过无值)
        doFetch(modelCode, tenantId)
                .subscribeOn(refreshScheduler)
                .doFinally(sig -> inFlight.set(false))
                .subscribe(result -> putPositive(key, result),
                        e -> log.debug("[THMP-CACHE] 后台刷新失败 (保旧值): key={}, err={}", key, e.getMessage()));
    }

    private Mono<ThmpContractClient.ResolveResult> doFetch(String modelCode, String tenantId) {
        return client.resolve(modelCode, tenantId)
                .map(this::requireSuccess)
                .doOnSubscribe(s -> fetchCount.incrementAndGet());
    }

    private ThmpContractClient.ResolveResult requireSuccess(ApiResponse<ThmpContractClient.ResolveResult> resp) {
        if (resp == null || !resp.isSuccess() || resp.getData() == null) {
            throw new IllegalStateException("resolve 信封失败: "
                    + (resp == null ? "null" : resp.getCode() + " " + resp.getMessage()));
        }
        return resp.getData();
    }

    private void putPositive(String key, ThmpContractClient.ResolveResult result) {
        store.put(key, new Entry(result, System.nanoTime()));
        evictIfOverCapacity(key);
    }

    private void putNegative(String key, Throwable error) {
        store.put(key, new Entry(error, System.nanoTime()));
    }

    /** LRU 淘汰: 超容时按 lastAccess 淘汰最久未访问 (刚写入的 key 不淘汰) */
    private void evictIfOverCapacity(String justWritten) {
        if (store.size() <= maxEntries) {
            return;
        }
        int toEvict = store.size() - maxEntries;
        store.entrySet().stream()
                .filter(e -> !e.getKey().equals(justWritten))
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessNanos))
                .limit(toEvict)
                .forEach(e -> store.remove(e.getKey(), e.getValue()));
    }

    private static final class Entry {
        final ThmpContractClient.ResolveResult value;
        final Throwable error;
        final long storedAtNanos;
        volatile long lastAccessNanos;

        Entry(ThmpContractClient.ResolveResult value, long now) {
            this.value = value;
            this.error = null;
            this.storedAtNanos = now;
            this.lastAccessNanos = now;
        }

        Entry(Throwable error, long now) {
            this.value = null;
            this.error = error;
            this.storedAtNanos = now;
            this.lastAccessNanos = now;
        }

        boolean isNegative() {
            return error != null;
        }

        void touch() {
            lastAccessNanos = System.nanoTime();
        }
    }
}
