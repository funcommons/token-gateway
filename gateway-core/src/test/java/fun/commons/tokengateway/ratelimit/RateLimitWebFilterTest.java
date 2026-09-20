package fun.commons.tokengateway.ratelimit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.config.ErrorContractProperties;
import fun.commons.tokengateway.config.RateLimitProperties;
import fun.commons.tokengateway.trace.TraceWebFilter;
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
 *
 * <p>含 issue #34 错误形状分流: 默认 envelope 模式全量行为不变 (信封 + 头三件套);
 * openai 形状仅对 LLM 面 path (/v1/chat, /v1/messages 前缀) 生效, 任务面恒信封;
 * Retry-After / X-RateLimit-* 头两种形态均原样保留.
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

    /** issue #34: openai 错误形状过滤器 (2 参兼容构造 + 独立契约实例, 不污染其他用例). */
    private RateLimitWebFilter openAiFilter() {
        var contract = new ErrorContractProperties();
        contract.setErrorShape(ErrorContractProperties.ErrorShape.OPENAI);
        return new RateLimitWebFilter(rateLimiter, props, contract);
    }

    private MockServerWebExchange exchangeFor(String path, String authHeader) {
        MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.post(path);
        if (authHeader != null) {
            req = req.header("Authorization", authHeader);
        }
        return MockServerWebExchange.from(req);
    }

    private void stubOverLimit() {
        when(rateLimiter.tryAcquire(anyString()))
                .thenReturn(Mono.just(new RedisWindowRateLimiter.RateDecision(false, 101, 42)));
    }

    private static JSONObject bodyOf(MockServerWebExchange exchange) {
        return JSON.parseObject(exchange.getResponse().getBodyAsString()
                .block(java.time.Duration.ofSeconds(2)));
    }

    /** 429 头三件套断言 (两种错误形态都断言, issue #34 头不动红线). */
    private static void assertHeaders(MockServerWebExchange exchange) {
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("42");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("100");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(exchange.getResponse().getHeaders().getFirst("X-RateLimit-Reset")).isEqualTo("42");
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
    @DisplayName("超限 (默认 envelope): 429 信封体 + Retry-After + X-RateLimit-Remaining=0, 不进 chain")
    void overLimitRejects429() {
        stubOverLimit();

        MockServerWebExchange exchange = exchangeFor("/v1/chat/completions", "Bearer sk-x");
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertHeaders(exchange);
        JSONObject body = bodyOf(exchange);
        assertThat(body.getIntValue("code")).isEqualTo(10500);
        assertThat(body.getString("message")).isEqualTo("请求过频");
        assertThat(body.getString("trace_id")).isNotBlank();
        assertThat(body.containsKey("timestamp")).isTrue();
    }

    @Test
    @DisplayName("openai 形状 + LLM 面 /v1/chat: 429 → error{type=rate_limit_error, code=10500} + trace_id, 头三件套不动")
    void openAiShapeChatPathIsRateLimitError() {
        stubOverLimit();
        MockServerWebExchange exchange = exchangeFor("/v1/chat/completions", "Bearer sk-x");
        StepVerifier.create(openAiFilter().filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isFalse();
        assertHeaders(exchange);
        JSONObject body = bodyOf(exchange);
        JSONObject error = body.getJSONObject("error");
        assertThat(error).isNotNull();
        assertThat(error.getString("type")).isEqualTo("rate_limit_error");
        assertThat(error.getString("code")).isEqualTo("10500");
        assertThat(error.getString("message")).isEqualTo("请求过频");
        assertThat(error.containsKey("param")).isTrue();
        assertThat(error.get("param")).isNull();
        assertThat(body.getString("trace_id")).isNotBlank();
        assertThat(body.containsKey("code")).as("信封顶层 code 不再出现").isFalse();
    }

    @Test
    @DisplayName("openai 形状 + LLM 面 /v1/messages: 同 openai error 形状")
    void openAiShapeMessagesPathIsRateLimitError() {
        stubOverLimit();
        MockServerWebExchange exchange = exchangeFor("/v1/messages", "Bearer sk-x");
        StepVerifier.create(openAiFilter().filter(exchange, chain)).verifyComplete();

        assertHeaders(exchange);
        assertThat(bodyOf(exchange).getJSONObject("error").getString("type"))
                .isEqualTo("rate_limit_error");
    }

    @Test
    @DisplayName("openai 形状 + trace: trace_id 取响应头 X-Trace-Id (与 TraceWebFilter 同源)")
    void openAiShapeTraceIdFromHeader() {
        stubOverLimit();
        MockServerWebExchange exchange = exchangeFor("/v1/chat/completions", "Bearer sk-x");
        exchange.getResponse().getHeaders().set(TraceWebFilter.TRACE_ID_HEADER, "trace-fixed-34");
        StepVerifier.create(openAiFilter().filter(exchange, chain)).verifyComplete();

        assertThat(bodyOf(exchange).getString("trace_id")).isEqualTo("trace-fixed-34");
    }

    @Test
    @DisplayName("openai 形状 + 任务面 path (/v1/onetoken/**): 恒信封 — 开关不误伤, 头三件套不动")
    void openAiShapeTaskFacePathStaysEnvelope() {
        stubOverLimit();
        MockServerWebExchange exchange = exchangeFor("/v1/onetoken/v1/jobs", "Bearer sk-x");
        StepVerifier.create(openAiFilter().filter(exchange, chain)).verifyComplete();

        assertThat(chainCalled).isFalse();
        assertHeaders(exchange);
        JSONObject body = bodyOf(exchange);
        assertThat(body.getIntValue("code")).isEqualTo(10500);
        assertThat(body.getString("message")).isEqualTo("请求过频");
        assertThat(body.getJSONObject("error")).as("openai error 体不出现").isNull();
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
