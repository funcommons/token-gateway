package fun.commons.tokengateway.ratelimit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import fun.commons.tokengateway.config.ErrorContractProperties;
import fun.commons.tokengateway.config.RateLimitProperties;
import fun.commons.tokengateway.exception.OpenAiErrorAssembler;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>错误形状分流 (issue #34): 本过滤器在 WebFilter 层直写响应, 不经
 * GlobalExceptionHandler 分流链 —— 故在拒绝分支内复用同一判定
 * ({@code gateway.error-shape=openai} 且 {@link OpenAiErrorAssembler#isLlmFacePath}):
 * LLM 面 path 出 OpenAI {@code {"error":{...}}} 形状 (429 → rate_limit_error,
 * 业务码 10500 字符串化, 头三件套原样保留); 任务面 / 内部端点 / 默认 envelope
 * 模式恒为既有六字段信封, 行为不变。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitWebFilter implements WebFilter {

    private final RedisWindowRateLimiter rateLimiter;
    private final RateLimitProperties props;
    private final ErrorContractProperties errorContract;

    /** 兼容构造 (存量测试/装配): 默认错误契约 (envelope, 行为与 #34 前一致). */
    public RateLimitWebFilter(RedisWindowRateLimiter rateLimiter, RateLimitProperties props) {
        this(rateLimiter, props, new ErrorContractProperties());
    }

    /** Spring 装配: gateway.error-shape 进限流拒绝分支 (issue #34). */
    @Autowired
    public RateLimitWebFilter(RedisWindowRateLimiter rateLimiter, RateLimitProperties props,
                              ErrorContractProperties errorContract) {
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.errorContract = errorContract;
    }

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
        String path = exchange.getRequest().getPath().value();
        log.warn("[RateLimit] 超限拦截: path={}, retryAfter={}s", path, decision.retryAfterSeconds());
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.getHeaders().set("X-RateLimit-Limit", String.valueOf(props.getLimit()));
        response.getHeaders().set("X-RateLimit-Remaining", "0");
        response.getHeaders().set("X-RateLimit-Reset", String.valueOf(decision.retryAfterSeconds()));
        byte[] bytes = bodyBytes(exchange, path);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 拒绝响应体 (issue #34 形状分流): openai 形状且 LLM 面 path → OpenAI error 体
     * ({@link OpenAiErrorAssembler#assemble}, 429 → rate_limit_error; traceId 取
     * X-Trace-Id 头, 缺失由 assembler 兜底 UUID; WriteNulls 保留 error.param=null 键,
     * 与 GlobalExceptionHandler 出口线格式一致); 其余一律既有六字段信封 (逐字节不变).
     */
    private byte[] bodyBytes(ServerWebExchange exchange, String path) {
        if (errorContract.isOpenAiShape() && OpenAiErrorAssembler.isLlmFacePath(path)) {
            return JSON.toJSONString(OpenAiErrorAssembler.assemble(
                            HttpStatus.TOO_MANY_REQUESTS.value(), ApiCode.TOO_MANY_REQUESTS.getCode(),
                            ApiCode.TOO_MANY_REQUESTS.getMessage(),
                            OpenAiErrorAssembler.currentTraceId(exchange)),
                    JSONWriter.Feature.WriteNulls).getBytes(StandardCharsets.UTF_8);
        }
        return JSON.toJSONString(ApiResponse.fail(ApiCode.TOO_MANY_REQUESTS))
                .getBytes(StandardCharsets.UTF_8);
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
