package fun.commons.tokengateway.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceWebFilter 单元测试.
 */
@DisplayName("TraceWebFilter 链路追踪")
class TraceWebFilterTest {

    private final TraceWebFilter filter = new TraceWebFilter();

    @Test
    @DisplayName("请求带 X-Trace-Id: 透传并回写响应头")
    void propagatesIncomingTraceId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/models").header("X-Trace-Id", "tid-123"));
        AtomicReference<String> seen = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange,
                        ex -> Mono.deferContextual(cv -> {
                            seen.set(cv.getOrDefault(TraceWebFilter.CONTEXT_KEY, (String) null));
                            return Mono.empty();
                        })))
                .verifyComplete();

        assertThat(seen.get()).isEqualTo("tid-123");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo("tid-123");
    }

    @Test
    @DisplayName("无 X-Trace-Id: 生成 UUID 并回写响应头")
    void generatesWhenMissing() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/models"));
        AtomicReference<String> seen = new AtomicReference<>();

        StepVerifier.create(filter.filter(exchange,
                        ex -> Mono.deferContextual(cv -> {
                            seen.set(cv.getOrDefault(TraceWebFilter.CONTEXT_KEY, (String) null));
                            return Mono.empty();
                        })))
                .verifyComplete();

        assertThat(seen.get()).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Trace-Id")).isEqualTo(seen.get());
    }
}
