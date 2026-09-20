package fun.commons.tokengateway.idempotency;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 幂等 key 存储 (回放式, issue #28): 占位去重 + 首响回放缓存.
 *
 * <p>三态 (由 tryAcquire 结果 + findResponse 组合区分):
 * <ul>
 *   <li>无键: tryAcquire 成功 → 按新请求执行</li>
 *   <li>占位无响应: tryAcquire 失败 + findResponse 空 → 进行中 (或首响不可缓存) → 409</li>
 *   <li>有响应: tryAcquire 失败 + findResponse 命中 → 回放首响</li>
 * </ul>
 */
public interface IdempotencyStore {

    /**
     * 尝试占位.
     *
     * @return true=占位成功 (首次), false=key 已存在 (重复请求)
     */
    Mono<Boolean> tryAcquire(String key, Duration ttl);

    /**
     * 释放占位 (非 2xx 失败时调用, 允许客户端同 key 重试).
     */
    Mono<Void> release(String key);

    /**
     * 保存首响 (回放缓存): 仅 2xx 非流式且不超限的响应由过滤器调用.
     * TTL 沿用占位 key 的剩余时长 (响应存续期不超原占位期).
     *
     * <p>默认空实现 (不缓存): 未覆写的实现退化为拒绝式语义 (重复请求恒 409).
     */
    default Mono<Void> saveResponse(String key, int status, String contentType, String body) {
        return Mono.empty();
    }

    /**
     * 查询首响.
     *
     * @return 命中回放缓存条目; 空 = 无缓存响应 (无键或占位尚未产出/不可缓存)
     */
    default Mono<IdempotentResponse> findResponse(String key) {
        return Mono.empty();
    }
}
