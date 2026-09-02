package fun.commons.tokengateway.task.state;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 任务元数据存储 (Redis, 可重建非状态真源——状态真源在 lotask4j).
 *
 * <p>键布局:
 * <ul>
 *   <li>{@code tgw:task:meta:{taskNo}} — JSON {lotaskId, preConsumeId, modality, notifyUrl, deadlineEpochMs}</li>
 *   <li>{@code tgw:task:rev:{lotaskId}} — 反查 taskNo (webhook 载荷只带 lotask id)</li>
 *   <li>{@code tgw:task:deadlines} — ZSET score=deadlineEpochMs member=taskNo (超时钟扫描)</li>
 *   <li>{@code tgw:task:pending} — SET member=taskNo (预扣未闭环清单, 对账兜底扫描)</li>
 *   <li>{@code tgw:task:result:{taskNo}} — 终态存储结果 (resources 已转 sig 代理 URL)</li>
 * </ul>
 * TTL = 任务超时窗口 + 24h 余量 (终态后轮询幂等仍需要).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskMetaStore {

    private static final String META_PREFIX = "tgw:task:meta:";
    private static final String REV_PREFIX = "tgw:task:rev:";
    private static final String RESULT_PREFIX = "tgw:task:result:";
    private static final String DEADLINES_ZSET = "tgw:task:deadlines";
    private static final String PENDING_SET = "tgw:task:pending";

    private final ReactiveStringRedisTemplate redis;

    /** 任务元数据 (create 时刻写入; deadlineEpochMs = 超时钟判定线). */
    public record TaskMeta(String lotaskId, String preConsumeId, String modality,
                           String notifyUrl, long deadlineEpochMs, String upstreamApiKey) {
    }

    /** create 时刻全量落账 (meta + 反查 + deadline 索引 + 预扣未闭环清单). */
    public Mono<Void> onCreated(String taskNo, TaskMeta meta, Duration ttl) {
        Mono<Boolean> metaWrite = redis.opsForValue()
                .set(META_PREFIX + taskNo, JSON.toJSONString(meta), ttl);
        Mono<Boolean> revWrite = redis.opsForValue()
                .set(REV_PREFIX + meta.lotaskId(), taskNo, ttl);
        Mono<Boolean> deadlineWrite = redis.opsForZSet()
                .add(DEADLINES_ZSET, taskNo, meta.deadlineEpochMs());
        Mono<Long> pendingWrite = redis.opsForSet()
                .add(PENDING_SET, taskNo);
        return Mono.when(metaWrite, revWrite, deadlineWrite, pendingWrite)
                .doOnError(e -> log.error("[TaskMeta] 落账失败 (对账兜底可补偿): taskNo={}, err={}",
                        taskNo, e.toString()))
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<TaskMeta> getMeta(String taskNo) {
        return redis.opsForValue().get(META_PREFIX + taskNo)
                .map(json -> JSON.parseObject(json, TaskMeta.class))
                .onErrorResume(e -> {
                    log.warn("[TaskMeta] 读取失败: taskNo={}, err={}", taskNo, e.toString());
                    return Mono.empty();
                });
    }

    /** webhook 反查: lotask id → taskNo. */
    public Mono<String> findTaskNo(String lotaskId) {
        return redis.opsForValue().get(REV_PREFIX + lotaskId)
                .onErrorResume(e -> Mono.empty());
    }

    /** 终态存储结果 (resources 已转 sig 代理 URL; poll 终态优先读这里). */
    public Mono<Void> saveTerminalResult(String taskNo, String resultJson, Duration ttl) {
        return redis.opsForValue().set(RESULT_PREFIX + taskNo, resultJson, ttl)
                .doOnError(e -> log.error("[TaskMeta] 终态结果写入失败: taskNo={}, err={}",
                        taskNo, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    public Mono<JSONObject> getTerminalResult(String taskNo) {
        return redis.opsForValue().get(RESULT_PREFIX + taskNo)
                .map(JSON::parseObject)
                .onErrorResume(e -> Mono.empty());
    }

    /** 超时钟扫描: 取 deadline 已到的 taskNo (批量上限 100). */
    public reactor.core.publisher.Flux<String> dueDeadlines(long nowEpochMs) {
        return redis.opsForZSet()
                .rangeByScore(DEADLINES_ZSET,
                        org.springframework.data.domain.Range.closed(0.0, (double) nowEpochMs),
                        org.springframework.data.redis.connection.RedisZSetCommands.Limit.limit()
                                .count(100))
                .onErrorResume(e -> {
                    log.warn("[TaskMeta] deadline 扫描失败: err={}", e.toString());
                    return reactor.core.publisher.Flux.empty();
                });
    }

    /** 移除 deadline 索引 (终态闭环/超时处理完毕). */
    public Mono<Void> clearDeadline(String taskNo) {
        return redis.opsForZSet().remove(DEADLINES_ZSET, taskNo)
                .then()
                .onErrorResume(e -> Mono.empty());
    }

    /** 预扣闭环 (终态处理完) → 移出对账清单. */
    public Mono<Void> closePending(String taskNo) {
        return redis.opsForSet().remove(PENDING_SET, taskNo)
                .then()
                .onErrorResume(e -> Mono.empty());
    }

    /** 对账兜底扫描: 预扣未闭环清单 (批量上限 100). */
    public reactor.core.publisher.Flux<String> pendingPreConsumes() {
        return redis.opsForSet().members(PENDING_SET)
                .take(100)
                .onErrorResume(e -> {
                    log.warn("[TaskMeta] 对账清单扫描失败: err={}", e.toString());
                    return reactor.core.publisher.Flux.empty();
                });
    }
}
