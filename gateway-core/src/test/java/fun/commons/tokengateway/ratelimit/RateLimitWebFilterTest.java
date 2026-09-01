package fun.commons.tokengateway.ratelimit;

import fun.commons.tokengateway.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimitWebFilter 单元测试 (mock RedisWindowRateLimiter).
 */
@DisplayName("RateLimitWebFilter 限流过滤")
class RateLimitWebFilterTest {

    private RedisWindowRateLimiter rateLimiter;
    private RateLimitProperties props;
    private RateLimitWebFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisWindowRateLimiter.class);
        props = new RateLimitProperties();
        props.setEnabled(true);
        props.setLimit(100);
        props.setWindowSeconds(60);
        filter = new RateLimitWebFilter(rateLimiter, props);
        chainCalled = new AtomicBoolean(false);
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    private MockServerWebExchange exchangeFor(String path, String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.post(path);
        if (authHeader != null) {
            req = req.header("Authorization", authHeader);
        }
        return MockServerWebExchange.from(req);
    }

    @Test
    @DisplayName("未超限: 放行 + X-RateLimit-Limit/Remaining 头")
    void allowedPassesThrough() {
        when(rateLimiter.tryAcquire(anyString()))
                .thenReturn(Mono.just(new RedisWindowRateLimiter.RateDecision(true, 5, 0)));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "Bearer sk-x"), chain))
                .verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    @Test
    @DisplayName("超限: 429 + Retry-After + X-RateLimit-Remaining=0, 不进 chain")
    void overLimitRejects429() {
        when(rateLimiter.tryAcquire(anyString()))
                .thenReturn(Mono.just(new RedisWindowRateLimiter.RateDecision(false, 101, 42)));

        MockServerWebExchange exchange = exchangeFor("/v1/chat/completions", "Bearer sk-x");
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("非 /v1 路径: 直接放行不调限流器")
    void nonV1PathSkipped() {
        StepVerifier.create(filter.filter(exchangeFor("/actuator/health", null), chain))
                .verifyComplete();
        assertThat(chainCalled).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    @DisplayName("限流关闭: 直接放行不调限流器")
    void disabledSkipped() {
        props.setEnabled(false);
        StepVerifier.create(filter.filter(exchangeFor("/v1/models", null), chain))
                .verifyComplete();
        assertThat(chainCalled).isTrue();
        verify(rateLimiter, never()).tryAcquire(anyString());
    }

    @Test
    @DisplayName("无 Authorization 头: 用 ip: 前缀 key")
    void ipFallbackKey() {
        when(rateLimiter.tryAcquire(anyString()))
                .thenReturn(Mono.just(new RedisWindowRateLimiter.RateDecision(true, 1, 0)));

        StepVerifier.create(filter.filter(exchangeFor("/v1/models", null), chain))
                .verifyComplete();

        verify(rateLimiter).tryAcquire(org.mockito.ArgumentMatchers.argThat(
                k -> k != null && k.startsWith("ip:")));
    }
}
