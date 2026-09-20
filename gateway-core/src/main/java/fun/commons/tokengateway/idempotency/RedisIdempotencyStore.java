package fun.commons.tokengateway.idempotency;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Redis 幂等存储 (SET NX PX 占位 + 首响回放缓存, issue #28).
 *
 * <p>结构 (单 key 双形态): {@code {key} = "1"} 占位 (SET NX PX, 请求在途/不可缓存)
 * 或首响 JSON ({@code {"status":200,"contentType":"application/json","body":"..."}})
 * (saveResponse 覆写占位值). 三态: 无键 (null) / 占位无响应 ("1") / 有响应 (JSON).
 * 响应 TTL 沿用占位 key 的剩余时长 (PEXPIRE 语义, 响应存续期不超原占位期).
 *
 * <p>容错: Redis 异常时 tryAcquire fail-open 视为占位成功 (不阻塞主链路),
 * saveResponse/findResponse 静默吞错 (findResponse 吞错=无缓存, 退化为拒绝式),
 * release 静默吞错 (key 至多活到 TTL).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyStore implements IdempotencyStore {

    /** 占位值 (区别于首响 JSON, 供 findResponse 判定三态). */
    static final String PLACEHOLDER_VALUE = "1";

    /** 占位值 hash 形态前缀 (body hash 规约: "h:<hex>"). */
    static final String HASH_PLACEHOLDER_PREFIX = "h:";

    /** 占位 key 缺失/无 TTL 时响应缓存的兜底 TTL (正常路径不会走到 — 保存时占位必然在). */
    private static final Duration RESPONSE_TTL_FALLBACK = Duration.ofHours(1);

    private final ReactiveStringRedisTemplate redis;

    @Override
    public Mono<Boolean> tryAcquire(String key, Duration ttl) {
        return redis.opsForValue().setIfAbsent(key, PLACEHOLDER_VALUE, ttl)
                .map(acquired -> Boolean.TRUE.equals(acquired))
                .onErrorResume(e -> {
                    log.warn("[Idempotency] Redis 异常, fail-open 放行: key={}, err={}", key, e.toString());
                    return Mono.just(true);
                });
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, Duration ttl, String bodyHash) {
        String value = HASH_PLACEHOLDER_PREFIX + (bodyHash == null ? "" : bodyHash);
        return redis.opsForValue().setIfAbsent(key, value, ttl)
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

    @Override
    public Mono<Void> saveResponse(String key, int status, String contentType, String body) {
        return saveResponse(key, status, contentType, body, null);
    }

    /**
     * 带 body hash 的首响保存 (hash 形态占位链路): 首响条目附 hash 供后续冲突检测.
     */
    public Mono<Void> saveResponse(String key, int status, String contentType, String body,
                                   String bodyHash) {
        JSONObject json = new JSONObject();
        json.put("status", status);
        json.put("contentType", contentType);
        json.put("body", body);
        if (bodyHash != null && !bodyHash.isEmpty()) {
            json.put("bodyHash", bodyHash);
        }
        // TTL 沿用占位 key 剩余时长 (覆写会清 TTL, 须显式回填)
        return redis.getExpire(key)
                .map(ttl -> ttl.isNegative() ? RESPONSE_TTL_FALLBACK : ttl)
                .defaultIfEmpty(RESPONSE_TTL_FALLBACK)
                .flatMap(ttl -> redis.opsForValue().set(key, json.toJSONString(), ttl))
                .doOnError(e -> log.warn("[Idempotency] 首响缓存失败 (等 TTL 过期): key={}, err={}",
                        key, e.toString()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    @Override
    public Mono<IdempotentResponse> findResponse(String key) {
        return redis.opsForValue().get(key)
                .flatMap(raw -> {
                    if (PLACEHOLDER_VALUE.equals(raw)) {
                        return Mono.empty(); // 占位无响应: 进行中或首响不可缓存
                    }
                    try {
                        JSONObject json = JSON.parseObject(raw);
                        return Mono.just(new IdempotentResponse(
                                json.getIntValue("status"),
                                json.getString("contentType"),
                                json.getString("body")));
                    } catch (Exception e) {
                        log.warn("[Idempotency] 首响缓存解析失败, 视为无缓存: key={}", key);
                        return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    log.warn("[Idempotency] Redis 异常, 视为无缓存: key={}, err={}", key, e.toString());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<IdempotentResponse> findResponse(String key, String bodyHash) {
        return redis.opsForValue().get(key)
                .flatMap(raw -> {
                    // 占位态: hash 形态占位与本次请求比对 (旧 "1" 占位无 hash, 视为一致放行至 409 语义)
                    if (PLACEHOLDER_VALUE.equals(raw)) {
                        return Mono.empty();
                    }
                    if (raw.startsWith(HASH_PLACEHOLDER_PREFIX)) {
                        String placeholderHash = raw.substring(HASH_PLACEHOLDER_PREFIX.length());
                        if (bodyHash != null && !bodyHash.isEmpty() && !bodyHash.equals(placeholderHash)) {
                            return Mono.error(new BodyMismatchException(key));
                        }
                        return Mono.empty();
                    }
                    try {
                        JSONObject json = JSON.parseObject(raw);
                        String cachedHash = json.getString("bodyHash");
                        if (bodyHash != null && !bodyHash.isEmpty() && cachedHash != null
                                && !bodyHash.equals(cachedHash)) {
                            return Mono.error(new BodyMismatchException(key));
                        }
                        return Mono.just(new IdempotentResponse(
                                json.getIntValue("status"),
                                json.getString("contentType"),
                                json.getString("body")));
                    } catch (BodyMismatchException mismatch) {
                        return Mono.error(mismatch);
                    } catch (Exception e) {
                        log.warn("[Idempotency] 首响缓存解析失败, 视为无缓存: key={}", key);
                        return Mono.empty();
                    }
                })
                .onErrorResume(BodyMismatchException.class, Mono::error)
                .onErrorResume(e -> {
                    if (e instanceof BodyMismatchException) {
                        return Mono.error(e);
                    }
                    log.warn("[Idempotency] Redis 异常, 视为无缓存: key={}, err={}", key, e.toString());
                    return Mono.empty();
                });
    }
}
