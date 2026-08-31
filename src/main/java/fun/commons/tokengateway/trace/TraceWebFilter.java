package fun.commons.tokengateway.trace;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Trace 过滤器: 读取或生成 X-Trace-Id, 写入 Reactor Context 并回写响应头.
 *
 * <p>下游经 Mono.deferContextual 取 {@link #CONTEXT_KEY}, 贯穿 access log / billing requestId.
 * <p>order 对齐单体 TraceFilter (HIGHEST_PRECEDENCE+5, 在限流过滤器之前).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class TraceWebFilter implements WebFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String CONTEXT_KEY = "traceId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String traceId = exchange.getRequest().getHeaders().getFirst(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);
        String finalTraceId = traceId;
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put(CONTEXT_KEY, finalTraceId));
    }
}
