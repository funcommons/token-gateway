package fun.commons.tokengateway.idempotency;

import fun.commons.tokengateway.config.IdempotencyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdempotencyWebFilter 单元测试 (mock IdempotencyStore).
 */
@DisplayName("IdempotencyWebFilter 幂等过滤")
class IdempotencyWebFilterTest {

    private IdempotencyStore store;
    private IdempotencyProperties props;
    private IdempotencyWebFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        props = new IdempotencyProperties();
        props.setEnabled(true);
        filter = new IdempotencyWebFilter(store, props);
        chainCalled = new AtomicBoolean(false);
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    private MockServerWebExchange exchangeFor(String path, String idemKey) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.post(path)
                .header("Authorization", "Bearer sk-x");
        if (idemKey != null) {
            req = req.header(IdempotencyWebFilter.IDEMPOTENCY_KEY_HEADER, idemKey);
        }
        return MockServerWebExchange.from(req);
    }

    @Test
    @DisplayName("首次请求: 占位成功 → 放行")
    void firstRequestPasses() {
        when(store.tryAcquire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-1"), chain))
                .verifyComplete();

        assertThat(chainCalled).isTrue();
        verify(store).tryAcquire("idem:Bearer sk-x:k-1", Duration.ofHours(48));
    }

    @Test
    @DisplayName("重复请求: 占位失败 → 409 + 10501, 不进 chain")
    void duplicateRejected() {
        when(store.tryAcquire(anyString(), any(Duration.class))).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = exchangeFor("/v1/chat/completions", "k-1");
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("5xx 失败: 释放占位允许重试")
    void serverErrorReleasesKey() {
        when(store.tryAcquire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(store.release(anyString())).thenReturn(Mono.empty());
        WebFilterChain failChain = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_GATEWAY);
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-2"), failChain))
                .verifyComplete();

        verify(store).release("idem:Bearer sk-x:k-2");
    }

    @Test
    @DisplayName("成功请求: 不释放占位")
    void successKeepsKey() {
        when(store.tryAcquire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-3"), chain))
                .verifyComplete();

        verify(store, never()).release(anyString());
    }

    @Test
    @DisplayName("chain 异常: 释放占位并继续抛出")
    void chainErrorReleasesAndRethrows() {
        when(store.tryAcquire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        when(store.release(anyString())).thenReturn(Mono.empty());
        WebFilterChain boomChain = exchange -> Mono.error(new RuntimeException("boom"));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-4"), boomChain))
                .verifyErrorMatches(e -> "boom".equals(e.getMessage()));

        verify(store).release("idem:Bearer sk-x:k-4");
    }

    @Test
    @DisplayName("未带 Idempotency-Key: 不干预, 不调存储")
    void noHeaderSkipped() {
        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", null), chain))
                .verifyComplete();
        assertThat(chainCalled).isTrue();
        verify(store, never()).tryAcquire(anyString(), any());
    }

    @Test
    @DisplayName("GET 请求: 不干预")
    void getSkipped() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/v1/models")
                        .header(IdempotencyWebFilter.IDEMPOTENCY_KEY_HEADER, "k-5"));
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
        assertThat(chainCalled).isTrue();
        verify(store, never()).tryAcquire(anyString(), any());
    }
}
