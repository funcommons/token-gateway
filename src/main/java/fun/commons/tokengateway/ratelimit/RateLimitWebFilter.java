package fun.commons.tokengateway.ratelimit;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.config.RateLimitProperties;
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

/**
 * 限流过滤器: /v1/** 按 Bearer key (fallback IP) 滑动窗口限流.
 *
 * <p>对齐单体 RateLimitFilter: 超限返回 429 + Retry-After + X-RateLimit-* 头.
 * <p>order 对齐单体 HIGHEST_PRECEDENCE+10 (在 trace 过滤器之后).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitWebFilter implements WebFilter {

    private final RedisWindowRateLimiter rateLimiter;
    private final RateLimitProperties props;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!props.isEnabled()
                || !exchange.getRequest().getPath().value().startsWith("/v1/")) {
            return chain.filter(exchange);
        }
        String key = resolveKey(exchange);
        return rateLimiter.tryAcquire(key)
                .flatMap(decision -> {
                    if (decision.allowed()) {
                        exchange.getResponse().getHeaders().set("X-RateLimit-Limit",
                                String.valueOf(props.getLimit()));
                        exchange.getResponse().getHeaders().set("X-RateLimit-Remaining",
                                String.valueOf(Math.max(0, props.getLimit() - decision.currentCount())));
                        return chain.filter(exchange);
                    }
                    return reject(exchange, decision);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, RedisWindowRateLimiter.RateDecision decision) {
        log.warn("[RateLimit] 超限拦截: path={}, retryAfter={}s",
                exchange.getRequest().getPath().value(), decision.retryAfterSeconds());
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.getHeaders().set("X-RateLimit-Limit", String.valueOf(props.getLimit()));
        response.getHeaders().set("X-RateLimit-Remaining", "0");
        response.getHeaders().set("X-RateLimit-Reset", String.valueOf(decision.retryAfterSeconds()));
        byte[] bytes = JSON.toJSONString(ApiResponse.fail(ApiCode.TOO_MANY_REQUESTS))
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private static String resolveKey(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && !auth.isBlank()) {
            return auth.trim();
        }
        String xApiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote != null ? remote.getAddress().getHostAddress() : "unknown");
    }
}
