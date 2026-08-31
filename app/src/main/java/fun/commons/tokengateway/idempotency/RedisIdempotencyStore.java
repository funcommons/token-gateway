package fun.commons.tokengateway.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis 幂等存储 (SET NX PX 占位).
 *
 * <p>容错: Redis 异常时 tryAcquire fail-open 视为占位成功 (不阻塞主链路),
 * release 静默吞错 (key 至多活到 TTL).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    private final ReactiveStringRedisTemplate redis;

    @Override
    public Mono<Boolean> tryAcquire(String key, Duration ttl) {
        return redis.opsForValue().setIfAbsent(key, "1", ttl)
                .map(acquired -> Boolean.TRUE.equals(acquired))
                .onErrorResume(e -> {
                    log.warn("[Idempotency] Redis 异常, fail-open 放行: key={}, err={}", key, e.toString());
                    return Mono.just(true);
                });
    }

    @Override
    public Mono<Void> release(String key) {
        return redis.delete(key)
                .doOnError(e -> log.warn("[Idempotency] 释放 key 失败 (等 TTL 过期): key={}, err={}",
                        key, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }
}
