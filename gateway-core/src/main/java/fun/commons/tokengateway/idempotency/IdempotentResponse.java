package fun.commons.tokengateway.idempotency;

/**
 * 幂等首响缓存条目 (回放语义, issue #28): 首次成功响应的 {status, contentType, body} 原文.
 *
 * <p>回放时按原 status/contentType/body 原样重建响应, 并附加
 * {@code Idempotency-Replayed: true} 响应头 (接入方据此区分新响应/回放).
 *
 * <p>body 以 UTF-8 文本承载 (网关响应统一 UTF-8); 仅 2xx 非流式且不超限的响应会被缓存.
 */
public record IdempotentResponse(int status, String contentType, String body) {
}
