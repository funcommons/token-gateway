package fun.commons.tokengateway.task.lotask;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * lotask4j bearer 令牌存储 (同租户多进程共享 —— 平台单会话互斥的适配).
 *
 * <p>平台 V4+ 会话策略 = 单租户单会话 (access-token.policies.TENANT.key=[tenant_id],
 * 同租户新 token 踢旧, 报「账号已在别处登录」): 网关与 Worker 各自登录会互踢,
 * bearer 必须跨进程共享. 容器内有 {@link ReactiveStringRedisTemplate} 时走 Redis
 * (多实例/多进程拓扑, 生产口径, 网关与 Worker 指向同一 Redis); Redis 不可用自动降级
 * 进程内 (单进程兼容) —— 读写全部容错, 存储故障不阻断业务.
 *
 * <p>令牌为敏感凭据: Redis 值仅平台可见网段可达, 与 tenant_secret 同级管控.
 */
@Slf4j
@Component
public class LotaskTokenStore {

    /** Redis 键 TTL 提前量 (早于 exp 一分钟过期, 续期判定另有 RENEW_AHEAD 余量). */
    private static final long TTL_EARLY_MS = 60_000;

    private final ReactiveStringRedisTemplate redis;
    private final Map<String, CachedToken> local = new ConcurrentHashMap<>();

    public LotaskTokenStore(ObjectProvider<ReactiveStringRedisTemplate> redis) {
        this.redis = redis == null ? null : redis.getIfAvailable();
    }

    /** 平台签发 bearer + 过期时刻 (毫秒 epoch). */
    public record CachedToken(String token, long expireAtMs) { }

    public Mono<CachedToken> get(String key) {
        if (redis == null) {
            return Mono.justOrEmpty(local.get(key));
        }
        return redis.opsForValue().get(key)
                .map(LotaskTokenStore::parse)
                .filter(java.util.Objects::nonNull)
                .onErrorResume(e -> {
                    log.warn("[LotaskTokenStore] redis get 失败, 降级进程内: {}", e.getMessage());
                    return Mono.justOrEmpty(local.get(key));
                });
    }

    public Mono<Void> put(String key, CachedToken token) {
        local.put(key, token);
        if (redis == null) {
            return Mono.empty();
        }
        long ttlMs = Math.max(token.expireAtMs() - System.currentTimeMillis() - TTL_EARLY_MS,
                TTL_EARLY_MS);
        return redis.opsForValue().set(key, JSON.toJSONString(token), Duration.ofMillis(ttlMs))
                .onErrorResume(e -> {
                    log.warn("[LotaskTokenStore] redis put 失败 (仅进程内生效): {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    public Mono<Void> clear(String key) {
        local.remove(key);
        if (redis == null) {
            return Mono.empty();
        }
        return redis.delete(key)
                .onErrorResume(e -> {
                    log.warn("[LotaskTokenStore] redis clear 失败: {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * 登录单飞锁 (单会话互斥下防两进程同时登录互踢): SETNX+TTL, 拿到锁的进程登录,
     * 其余进程等它回写 token 后直接复用. 无 Redis (单进程) 时恒 true — 无锁必要.
     */
    public Mono<Boolean> tryLock(String key, Duration ttl) {
        if (redis == null) {
            return Mono.just(true);
        }
        return redis.opsForValue().setIfAbsent(key, "1", ttl)
                .onErrorResume(e -> {
                    log.warn("[LotaskTokenStore] redis tryLock 失败 (按获锁处理): {}", e.getMessage());
                    return Mono.just(true);
                });
    }

    /** 释放登录单飞锁 (TTL 兜底, 忘释放只影响一个登录窗口). */
    public Mono<Void> unlock(String key) {
        if (redis == null) {
            return Mono.empty();
        }
        return redis.delete(key)
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private static CachedToken parse(String json) {
        try {
            JSONObject d = JSON.parseObject(json);
            return new CachedToken(d.getString("token"), d.getLongValue("expireAtMs"));
        } catch (Exception e) {
            return null;
        }
    }
}
