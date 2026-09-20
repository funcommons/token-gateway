package fun.commons.tokengateway.relay.billing;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * settle/refund 失败待重放队列 (issue #23, Redis ZSET; 参照任务面 tgw:task:pending 模式).
 *
 * <p>键布局: {@code tgw:billing:pending} — ZSET,
 * score = 下次重试 epoch ms, member = {@link BillingPendingRecord} JSON 单行.
 *
 * <p>生命周期: 基础设施失败 {@link #enqueue} (score = now + base-backoff) →
 * {@link BillingReconcileJob} 到期取 ({@link #duePending}, 分页上限内) →
 * 重放成功 {@link #remove} (zrem) / 失败 {@link #reschedule} (retries+1 指数退避重排) /
 * 耗尽 {@link #remove} + 死信结构化日志.
 *
 * <p>多实例无分布式锁: 并发重放同一 preConsumeId 由能力面幂等兜底, 最坏多发一次; 不丢是第一目标.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BillingPendingStore {

    /** 待重放 ZSET 键 (score = 下次重试 epoch ms). */
    public static final String PENDING_ZSET = "tgw:billing:pending";

    private final ReactiveStringRedisTemplate redis;
    private final BillingReconcileProperties properties;

    /** 扫描条目: 解析后的记录 + 原始 member (zrem 须按原始字节, 防 round-trip 漂移). */
    public record BillingPendingEntry(BillingPendingRecord record, String member) {
    }

    /** 首次入队: score = now + base-backoff (给能力面故障一个喘息窗, 防同秒重放风暴). */
    public Mono<Boolean> enqueue(BillingPendingRecord record) {
        long nextRetryAt = System.currentTimeMillis() + properties.getBaseBackoff().toMillis();
        return redis.opsForZSet().add(PENDING_ZSET, JSON.toJSONString(record), nextRetryAt)
                .doOnSuccess(ok -> log.warn(
                        "[BillingPending] settle/refund 失败入待重放队列: type={}, preConsumeId={}, "
                                + "requestId={}, retries={}, nextRetryAt={}",
                        record.type(), record.preConsumeId(), record.requestId(), record.retries(),
                        nextRetryAt))
                .doOnError(e -> log.error("[BillingPending] 入队失败: preConsumeId={}, err={}",
                        record.preConsumeId(), e.toString()))
                // 入队失败 (Redis 不可用) 兜底: 死信快照保参数不丢
                .onErrorResume(e -> {
                    BillingDeadLetters.log(record, "enqueue", "pending 入队失败: " + e.getMessage());
                    return Mono.just(false);
                });
    }

    /** 到期扫描: score ∈ [0, now] 的条目 (批量上限 limit, 参照任务面扫描节拍). */
    public Flux<BillingPendingEntry> duePending(int limit) {
        double now = System.currentTimeMillis();
        return redis.opsForZSet()
                .rangeByScore(PENDING_ZSET, Range.closed(0.0, now),
                        RedisZSetCommands.Limit.limit().count(limit))
                .concatMap(this::parseEntry)
                .onErrorResume(e -> {
                    log.warn("[BillingPending] 到期扫描失败, 下轮再试: err={}", e.toString());
                    return Flux.empty();
                });
    }

    /** 单条解析: 坏成员 (不可解析 JSON) 直接死信出队, 防毒丸常驻阻塞扫描. */
    private Flux<BillingPendingEntry> parseEntry(String member) {
        try {
            BillingPendingRecord record = JSON.parseObject(member, BillingPendingRecord.class);
            return Flux.just(new BillingPendingEntry(record, member));
        } catch (Exception e) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "billing-dead-letter");
            payload.put("phase", "scan");
            payload.put("reason", "pending member 解析失败: " + e.getMessage());
            payload.put("rawMember", member);
            log.error("[Billing-DeadLetter] {}", JSON.toJSONString(payload));
            return redis.opsForZSet().remove(PENDING_ZSET, member)
                    .thenMany(Flux.<BillingPendingEntry>empty());
        }
    }

    /** 出队 (重放成功闭环 / 重放耗尽死信共用同一 zrem). */
    public Mono<Long> remove(BillingPendingEntry entry) {
        return redis.opsForZSet().remove(PENDING_ZSET, entry.member())
                .doOnError(e -> log.error("[BillingPending] 出队失败: preConsumeId={}, err={}",
                        entry.record().preConsumeId(), e.toString()))
                .onErrorResume(e -> Mono.just(0L));
    }

    /**
     * 重放失败重排: 先 zadd 新成员 (retries+1, score = 下次重试时刻) 再 zrem 旧成员.
     * <p>顺序不可反: 两步之间进程崩溃, 反序会丢 pending (违背兜底初衷); 正序最坏留下
     * 旧成员多重放一次 (preConsumeId 幂等, 无副作用). 新旧 member 字节不同
     * (retries 递增), 直接 zadd 不清旧会双成员常驻, 故必须 zrem.
     */
    public Mono<Boolean> reschedule(BillingPendingEntry entry, BillingPendingRecord next,
                                    long nextRetryAt) {
        return redis.opsForZSet().add(PENDING_ZSET, JSON.toJSONString(next), nextRetryAt)
                .flatMap(added -> redis.opsForZSet().remove(PENDING_ZSET, entry.member())
                        .map(removed -> Boolean.TRUE.equals(added)))
                .doOnError(e -> log.error("[BillingPending] 重排失败: preConsumeId={}, err={}",
                        entry.record().preConsumeId(), e.toString()))
                .onErrorResume(e -> Mono.just(false));
    }
}
