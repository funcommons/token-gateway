package fun.commons.tokengateway.idempotency;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.config.IdempotencyProperties;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 幂等过滤器 (拒绝式): 客户端带 Idempotency-Key 头的 POST /v1/** 请求去重.
 *
 * <p>语义:
 * <ul>
 *   <li>首次: Redis 占位成功 → 放行</li>
 *   <li>重复 (在途或已成功): → 409 + 10501 拒绝, 防止重试重复扣费</li>
 *   <li>上次 5xx 失败: 释放占位, 允许同 key 重试</li>
 *   <li>未带头: 不干预 (幂等是客户端可选能力)</li>
 * </ul>
 * <p>key 作用域 = apiKey + Idempotency-Key (apiKey 天然隔离租户/用户).
 * <p>order 在限流 (+10) 之后.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class IdempotencyWebFilter implements WebFilter {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final IdempotencyStore store;
    private final IdempotencyProperties props;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!props.isEnabled()
                || !"POST".equalsIgnoreCase(exchange.getRequest().getMethod().name())
                || !exchange.getRequest().getPath().value().startsWith("/v1/")) {
            return chain.filter(exchange);
        }
        String idemKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (idemKey == null || idemKey.isBlank()) {
            return chain.filter(exchange);
        }
        String redisKey = props.getKeyPrefix() + resolveApiKey(exchange) + ":" + idemKey.trim();
        Duration ttl = Duration.ofHours(props.getTtlHours());
        return store.tryAcquire(redisKey, ttl)
                .flatMap(acquired -> {
                    if (!acquired) {
                        log.warn("[Idempotency] 重复请求拦截: path={}, key={}",
                                exchange.getRequest().getPath().value(), idemKey);
                        return reject(exchange);
                    }
                    return chain.filter(exchange)
                            .then(Mono.defer(() -> releaseOnServerError(exchange, redisKey)))
                            .onErrorResume(err -> store.release(redisKey).then(Mono.error(err)));
                });
    }

    /**
     * 5xx 视为未成功, 释放占位允许重试; 4xx/成功 保持占位.
     */
    private Mono<Void> releaseOnServerError(ServerWebExchange exchange, String redisKey) {
        var status = exchange.getResponse().getStatusCode();
        if (status != null && status.is5xxServerError()) {
            return store.release(redisKey);
        }
        return Mono.empty();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.CONFLICT);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = JSON.toJSONString(ApiResponse.fail(ApiCode.DUPLICATE_SUBMIT))
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private static String resolveApiKey(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && !auth.isBlank()) {
            return auth.trim();
        }
        String xApiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        return xApiKey != null ? xApiKey.trim() : "anonymous";
    }
}
