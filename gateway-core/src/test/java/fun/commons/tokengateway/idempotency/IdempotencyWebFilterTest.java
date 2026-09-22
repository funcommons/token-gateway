package fun.commons.tokengateway.idempotency;

import fun.commons.tokengateway.config.IdempotencyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IdempotencyWebFilter 单元测试 (mock / 内存 IdempotencyStore, 回放语义 issue #28).
 */
@DisplayName("IdempotencyWebFilter 幂等过滤 (回放式)")
class IdempotencyWebFilterTest {

    private IdempotencyStore store;
    private IdempotencyProperties props;
    private IdempotencyWebFilter filter;
    private AtomicBoolean chainCalled;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        store = mock(IdempotencyStore.class);
        // 回放语义 (issue #28): 默认桩全 store 交互, 用例内按需覆盖
        lenient().when(store.release(anyString())).thenReturn(Mono.empty());
        lenient().when(store.findResponse(anyString(), any())).thenReturn(Mono.empty());
        lenient().when(store.saveResponse(anyString(), anyInt(), any(), any(), any()))
                .thenReturn(Mono.empty());
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

    /** chain 替身: 写出 200 + JSON body (回放缓存的命中对象). */
    private static WebFilterChain jsonChain(String body) {
        return exchange -> writeBody(exchange.getResponse(), HttpStatus.OK,
                MediaType.APPLICATION_JSON, body);
    }

    private static Mono<Void> writeBody(ServerHttpResponse response, HttpStatus status,
                                        MediaType contentType, String body) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(contentType);
        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /** 内存三态存储 (占位 + 首响), 供回放流程级断言. */
    private static final class InMemoryStore implements IdempotencyStore {
        final Map<String, String> placeholders = new ConcurrentHashMap<>();
        final Map<String, IdempotentResponse> responses = new ConcurrentHashMap<>();

        @Override
        public Mono<Boolean> tryAcquire(String key, Duration ttl) {
            return Mono.just(placeholders.putIfAbsent(key, "1") == null);
        }

        @Override
        public Mono<Void> release(String key) {
            placeholders.remove(key);
            return Mono.empty();
        }

        @Override
        public Mono<Void> saveResponse(String key, int status, String contentType, String body) {
            responses.put(key, new IdempotentResponse(status, contentType, body));
            return Mono.empty();
        }

        @Override
        public Mono<IdempotentResponse> findResponse(String key) {
            return Mono.justOrEmpty(responses.get(key));
        }
    }

    private static String redisKeyOf(String idemKey) {
        return "idem:Bearer sk-x:" + idemKey;
    }

    // ---------- 占位/释放 (mock 存储语义) ----------

    @Test
    @DisplayName("首次请求: 占位成功 → 放行")
    void firstRequestPasses() {
        when(store.tryAcquire(anyString(), any(Duration.class), any())).thenReturn(Mono.just(true));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-1"), chain))
                .verifyComplete();

        assertThat(chainCalled).isTrue();
        verify(store).tryAcquire(eq("idem:Bearer sk-x:k-1"), eq(Duration.ofHours(48)), any());
    }

    @Test
    @DisplayName("重复请求 (占位在途): findResponse 空 + 再占位失败 → 409 + 10501 处理中, 不进 chain")
    void duplicateRejected() {
        when(store.tryAcquire(anyString(), any(Duration.class), any())).thenReturn(Mono.just(false));
        when(store.findResponse(anyString(), any())).thenReturn(Mono.empty());

        MockServerWebExchange exchange = exchangeFor("/v1/chat/completions", "k-1");
        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("10501").contains("处理中");
        verify(store, times(2)).tryAcquire(anyString(), any(Duration.class), any());
    }

    @Test
    @DisplayName("无键竞态 (已释放/过期): findResponse 空 + 再占位成功 → 放行按新请求")
    void releasedKeyRacesIntoNewRequest() {
        when(store.tryAcquire(eq("idem:Bearer sk-x:k-1"), eq(Duration.ofHours(48)), any()))
                .thenReturn(Mono.just(false), Mono.just(true));
        when(store.findResponse(eq("idem:Bearer sk-x:k-1"), any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-1"), chain))
                .verifyComplete();

        assertThat(chainCalled).isTrue();
    }

    @Test
    @DisplayName("5xx 失败: 释放占位允许重试")
    void serverErrorReleasesKey() {
        when(store.tryAcquire(anyString(), any(Duration.class), any())).thenReturn(Mono.just(true));
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
    @DisplayName("成功请求: 缓存首响, 不释放占位")
    void successKeepsKey() {
        when(store.tryAcquire(anyString(), any(Duration.class), any())).thenReturn(Mono.just(true));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-3"),
                        jsonChain("{\"ok\":true}")))
                .verifyComplete();

        verify(store, never()).release(anyString());
        verify(store).saveResponse(eq("idem:Bearer sk-x:k-3"), eq(200), any(), any(), any());
    }

    @Test
    @DisplayName("chain 异常: 释放占位并继续抛出")
    void chainErrorReleasesAndRethrows() {
        when(store.tryAcquire(anyString(), any(Duration.class), any())).thenReturn(Mono.just(true));
        when(store.release(anyString())).thenReturn(Mono.empty());
        WebFilterChain boomChain = exchange -> Mono.error(new RuntimeException("boom"));

        StepVerifier.create(filter.filter(exchangeFor("/v1/chat/completions", "k-4"), boomChain))
                .verifyErrorMatches(e -> "boom".equals(e.getMessage()));

        verify(store).release("idem:Bearer sk-x:k-4");
    }

    @Test
    @DisplayName("body 冲突 (hash 规约): 同 key 异 body → 422, 不回放不进 chain")
    void bodyMismatchRejected422() {
        when(store.tryAcquire(anyString(), any(Duration.class), any())).thenReturn(Mono.just(false));
        when(store.findResponse(anyString(), any()))
                .thenReturn(Mono.error(new IdempotencyStore.BodyMismatchException("idem:Bearer sk-x:k-m")));

        MockServerWebExchange mismatchExchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions")
                        .header("Authorization", "Bearer sk-x")
                        .header(IdempotencyWebFilter.IDEMPOTENCY_KEY_HEADER, "k-m")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"model\":\"different\"}"));

        StepVerifier.create(filter.filter(mismatchExchange, chain))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(mismatchExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mismatchExchange.getResponse().getHeaders().getFirst("Content-Type")).isNotNull();
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

    // ---------- multipart 透传契约 (回归 2026-09-21-04 BL03 P1-1) ----------

    /** multipart 上传请求替身 (带 Idempotency-Key, 即 AigcImagePicker 固定携带的形状). */
    private MockServerWebExchange multipartExchange(String path, String idemKey, String body) {
        MockServerHttpRequest.BodyBuilder req = MockServerHttpRequest.post(path)
                .header("Authorization", "Bearer sk-x")
                .contentType(MediaType.MULTIPART_FORM_DATA);
        if (idemKey != null) {
            req = req.header(IdempotencyWebFilter.IDEMPOTENCY_KEY_HEADER, idemKey);
        }
        return MockServerWebExchange.from(req.body(body));
    }

    @Test
    @DisplayName("multipart × 带键: 透传放行进 chain, 零幂等裁决 (不占位不回放不 422/409)")
    void multipartWithKeyPassesThrough() {
        MockServerWebExchange exchange = multipartExchange("/v1/upload/image", "k-mp-1",
                "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.png\"\r\n"
                        + "\r\nPNG\r\n--boundary--\r\n");

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(chainCalled).isTrue();
        verify(store, never()).tryAcquire(anyString(), any(Duration.class), any());
        verify(store, never()).findResponse(anyString(), any());
        verify(store, never()).saveResponse(anyString(), anyInt(), any(), any(), any());
        verify(store, never()).release(anyString());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(exchange.getResponse().getHeaders().getFirst(IdempotencyWebFilter.REPLAYED_HEADER))
                .isNull();
    }

    @Test
    @DisplayName("multipart 请求体原样送达下游 (filter 不读体不重包, 边界字节完整)")
    void multipartBodyDeliveredIntactToDownstream() {
        String multipartBody = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; "
                + "filename=\"a.png\"\r\n\r\nPNGBYTES\r\n--boundary--\r\n";
        MockServerWebExchange exchange = multipartExchange("/v1/upload/image", "k-mp-2", multipartBody);
        StringBuilder downstreamBody = new StringBuilder();

        WebFilterChain passthroughChain = downstream -> downstream.getRequest().getBody()
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    org.springframework.core.io.buffer.DataBufferUtils.release(buffer);
                    downstreamBody.append(new String(bytes, StandardCharsets.UTF_8));
                    return buffer;
                })
                .then();

        StepVerifier.create(filter.filter(exchange, passthroughChain))
                .verifyComplete();

        assertThat(downstreamBody.toString()).isEqualTo(multipartBody);
    }

    @Test
    @DisplayName("multipart × 带键 × 同 key 重复提交: 恒按新请求放行 (无回放无 409)")
    void multipartSameKeyTwiceNeverReplays() {
        InMemoryStore mem = new InMemoryStore();
        filter = new IdempotencyWebFilter(mem, props);

        for (int i = 0; i < 2; i++) {
            MockServerWebExchange exchange = multipartExchange("/v1/upload/image", "k-mp-3", "payload");
            AtomicBoolean entered = new AtomicBoolean(false);
            StepVerifier.create(filter.filter(exchange, ex -> {
                        entered.set(true);
                        return writeBody(ex.getResponse(), HttpStatus.OK,
                                MediaType.APPLICATION_JSON, "{\"ok\":true}");
                    }))
                    .verifyComplete();
            assertThat(entered).as("第 %d 次提交应按新请求放行", i + 1).isTrue();
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(mem.placeholders).isEmpty();
        assertThat(mem.responses).isEmpty();
    }

    // ---------- 回放语义 (内存存储流程, issue #28) ----------

    @Test
    @DisplayName("回放: 首次 200 缓存 → 同 key 二次回放同 body + Idempotency-Replayed, 不进 chain")
    void firstSuccessThenReplay() {
        InMemoryStore mem = new InMemoryStore();
        filter = new IdempotencyWebFilter(mem, props);

        MockServerWebExchange first = exchangeFor("/v1/onetoken/images", "k-r1");
        StepVerifier.create(filter.filter(first, jsonChain("{\"task_no\":\"T1\"}")))
                .verifyComplete();
        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mem.responses).containsKey(redisKeyOf("k-r1"));

        MockServerWebExchange second = exchangeFor("/v1/onetoken/images", "k-r1");
        AtomicBoolean secondChainCalled = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(second, ex -> {
                    secondChainCalled.set(true);
                    return Mono.empty();
                }))
                .verifyComplete();

        assertThat(secondChainCalled).isFalse();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getResponse().getBodyAsString().block())
                .isEqualTo("{\"task_no\":\"T1\"}");
        assertThat(second.getResponse().getHeaders().getFirst(IdempotencyWebFilter.REPLAYED_HEADER))
                .isEqualTo("true");
        assertThat(second.getResponse().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("处理中并发: 占位无响应 → 二次 409 + 10501, 不进 chain")
    void concurrentInProgressRejects() {
        InMemoryStore mem = new InMemoryStore();
        mem.placeholders.put(redisKeyOf("k-r2"), "1");
        filter = new IdempotencyWebFilter(mem, props);

        MockServerWebExchange second = exchangeFor("/v1/onetoken/images", "k-r2");
        StepVerifier.create(filter.filter(second, chain))
                .verifyComplete();

        assertThat(chainCalled).isFalse();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getResponse().getBodyAsString().block())
                .contains("10501").contains("处理中");
    }

    @Test
    @DisplayName("非 2xx release: 首次 404 释放占位 → 同 key 重试按新请求 (失败不占键)")
    void non2xxReleasesThenRetryExecutes() {
        InMemoryStore mem = new InMemoryStore();
        filter = new IdempotencyWebFilter(mem, props);
        WebFilterChain notFoundChain = exchange ->
                writeBody(exchange.getResponse(), HttpStatus.NOT_FOUND,
                        MediaType.APPLICATION_JSON, "{\"code\":10400}");

        MockServerWebExchange first = exchangeFor("/v1/onetoken/images", "k-r4");
        StepVerifier.create(filter.filter(first, notFoundChain))
                .verifyComplete();
        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mem.placeholders).doesNotContainKey(redisKeyOf("k-r4"));
        assertThat(mem.responses).isEmpty();

        // 同 key 重试 → 按新请求执行 (chain 再次进入)
        AtomicBoolean retried = new AtomicBoolean(false);
        StepVerifier.create(filter.filter(exchangeFor("/v1/onetoken/images", "k-r4"), ex -> {
                    retried.set(true);
                    return Mono.empty();
                }))
                .verifyComplete();
        assertThat(retried).isTrue();
    }

    @Test
    @DisplayName("流式 2xx: 占位保留不缓存 → 二次 409 (SSE 不支持回放)")
    void streaming2xxKeepsPlaceholder() {
        InMemoryStore mem = new InMemoryStore();
        filter = new IdempotencyWebFilter(mem, props);
        WebFilterChain sseChain = exchange -> {
            ServerHttpResponse resp = exchange.getResponse();
            resp.setStatusCode(HttpStatus.OK);
            resp.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
            DataBuffer buffer = resp.bufferFactory()
                    .wrap("data: hi\n\n".getBytes(StandardCharsets.UTF_8));
            // flush-per-chunk = 流式写路径
            return resp.writeAndFlushWith(Mono.just(Mono.just(buffer)));
        };

        MockServerWebExchange first = exchangeFor("/v1/chat/completions", "k-r5");
        StepVerifier.create(filter.filter(first, sseChain))
                .verifyComplete();
        assertThat(first.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mem.responses).doesNotContainKey(redisKeyOf("k-r5"));
        assertThat(mem.placeholders).containsKey(redisKeyOf("k-r5"));

        MockServerWebExchange second = exchangeFor("/v1/chat/completions", "k-r5");
        StepVerifier.create(filter.filter(second, chain))
                .verifyComplete();
        assertThat(chainCalled).isFalse();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getResponse().getBodyAsString().block())
                .contains("10501");
    }

    @Test
    @DisplayName("超限 2xx (>1MB): 占位保留不缓存 (容量护栏) → 二次 409")
    void oversized2xxKeepsPlaceholder() {
        InMemoryStore mem = new InMemoryStore();
        filter = new IdempotencyWebFilter(mem, props);
        String bigBody = "x".repeat(IdempotencyWebFilter.MAX_CACHED_BODY_BYTES + 1);

        MockServerWebExchange first = exchangeFor("/v1/chat/completions", "k-r6");
        StepVerifier.create(filter.filter(first, jsonChain(bigBody)))
                .verifyComplete();
        assertThat(mem.responses).doesNotContainKey(redisKeyOf("k-r6"));
        assertThat(mem.placeholders).containsKey(redisKeyOf("k-r6"));

        MockServerWebExchange second = exchangeFor("/v1/chat/completions", "k-r6");
        StepVerifier.create(filter.filter(second, chain))
                .verifyComplete();
        assertThat(chainCalled).isFalse();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
