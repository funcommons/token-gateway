package fun.commons.tokengateway.idempotency;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 幂等 key 存储 (拒绝式: 占位成功=首次请求, 已存在=重复请求).
 */
public interface IdempotencyStore {

    /**
     * 尝试占位.
     *
     * @return true=占位成功 (首次), false=key 已存在 (重复请求)
     */
    Mono<Boolean> tryAcquire(String key, Duration ttl);

    /**
     * 释放占位 (5xx 失败时调用, 允许客户端同 key 重试).
     */
    Mono<Void> release(String key);
}
