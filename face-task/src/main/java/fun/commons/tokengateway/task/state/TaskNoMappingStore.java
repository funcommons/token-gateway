package fun.commons.tokengateway.task.state;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * task_no → lotask 任务 ID 映射 (《05》§10 R2 网关侧补偿: 平台按 OpenID 查询,
 * 调用方持网关 task_no, 映射存 Redis; 可重建非状态真源——状态真源在 lotask4j).
 *
 * <p>TTL = 任务超时窗口 + 24h 余量 (终态后仍需支持轮询幂等).
 * Redis 异常: put 失败仅告警 (submit 已成功, poll 将 404 直至映射恢复——接受,
 * M2.5c 对账任务可重建); get 失败按映射缺失处理 (调用方退避重试).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskNoMappingStore {

    private static final String KEY_PREFIX = "tgw:task:map:";

    private final ReactiveStringRedisTemplate redis;

    public Mono<Void> put(String taskNo, String lotaskId, Duration ttl) {
        return redis.opsForValue().set(KEY_PREFIX + taskNo, lotaskId, ttl)
                .doOnError(e -> log.error("[TaskMap] 映射写入失败 (poll 将 404 直至恢复): taskNo={}, err={}",
                        taskNo, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    /** @return lotask 任务 ID; 缺失/异常 → empty */
    public Mono<String> get(String taskNo) {
        return redis.opsForValue().get(KEY_PREFIX + taskNo)
                .onErrorResume(e -> {
                    log.warn("[TaskMap] 映射读取失败: taskNo={}, err={}", taskNo, e.toString());
                    return Mono.empty();
                });
    }
}
