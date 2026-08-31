package fun.commons.tokengateway.ratelimit;

import fun.commons.tokengateway.config.RateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Redis ZSET 滑动窗口限流器 (reactive, 移植单体 RedisLuaWindowStore 语义).
 *
 * <p>单 Lua 一次往返完成 purge + add + count + oldest, 原子性由 Redis 保证.
 * <p>容错: Redis 不可达 / 脚本异常 → fail-open 放行 (限流宁可失效不拖垮主链路).
 */
@Slf4j
@Component
public class RedisWindowRateLimiter {

    /**
     * 合并脚本: 清窗 → 写入 → 计数 → 最旧条目.
     * ARGV[1]=now, ARGV[2]=windowMs, ARGV[3]=member. 返回 [count, oldestScore].
     */
    static final String ACQUIRE_LUA = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local member = ARGV[3]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window + 1000)
            local count = redis.call('ZCARD', key)
            local first = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
            local oldest = 0
            if #first >= 2 then
                oldest = tonumber(first[2])
            end
            return {count, oldest}
            """;

    private final ReactiveStringRedisTemplate redis;
    private final RateLimitProperties props;
    private final RedisScript<List> acquireScript;

    public RedisWindowRateLimiter(ReactiveStringRedisTemplate redis, RateLimitProperties props) {
        this.redis = redis;
        this.props = props;
        this.acquireScript = new DefaultRedisScript<>(ACQUIRE_LUA, List.class);
    }

    /**
     * 窗口判定结果.
     *
     * @param allowed      是否放行
     * @param currentCount 窗口内当前请求数 (含本次)
     * @param retryAfterSeconds 超限时建议重试秒数 (最旧条目滑出窗口的时间)
     */
    public record RateDecision(boolean allowed, long currentCount, long retryAfterSeconds) {
    }

    /**
     * 尝试 acquire: 计数 +1 后判断是否超限.
     * <p>任何异常 fail-open 返回 allowed.
     */
    @SuppressWarnings("unchecked")
    public Mono<RateDecision> tryAcquire(String key) {
        long now = System.currentTimeMillis();
        long windowMs = props.getWindowSeconds() * 1000L;
        return redis.execute(acquireScript,
                        List.of(props.getKeyPrefix() + key),
                        List.of(String.valueOf(now), String.valueOf(windowMs),
                                UUID.randomUUID().toString()))
                .next()
                .map(result -> {
                    List<Number> r = (List<Number>) result;
                    long count = r.size() > 0 && r.get(0) != null ? r.get(0).longValue() : 0;
                    long oldest = r.size() > 1 && r.get(1) != null ? r.get(1).longValue() : 0;
                    boolean allowed = count <= props.getLimit();
                    long retryAfter = oldest > 0
                            ? Math.max(1, (oldest + windowMs - now + 999) / 1000) : props.getWindowSeconds();
                    return new RateDecision(allowed, count, retryAfter);
                })
                .onErrorResume(e -> {
                    log.warn("[RateLimit] Redis 异常, fail-open 放行: key={}, err={}", key, e.toString());
                    return Mono.just(new RateDecision(true, 0, 0));
                });
    }
}
