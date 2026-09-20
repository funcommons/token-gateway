package fun.commons.tokengateway.idempotency;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.config.IdempotencyProperties;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 幂等过滤器 (回放式, issue #28): 客户端带 Idempotency-Key 头的 POST /v1/** 请求去重.
 *
 * <p>语义 (三态):
 * <ul>
 *   <li>首次: Redis 占位成功 → 放行; 响应完成后按缓存资格处置 —
 *       2xx 非流式且 body ≤ {@value #MAX_CACHED_BODY_BYTES} 字节 → 缓存首响 (TTL 内同 key 回放);
 *       流式 (text/event-stream)/超限/无 body 的 2xx → 仅保留占位不缓存;
 *       非 2xx 或链路异常 → 释放占位 (失败不占键, 允许同 key 重试)</li>
 *   <li>重复: 命中首响缓存 → 原样回放 (同 status/contentType/body +
 *       {@code Idempotency-Replayed: true} 响应头); 占位无响应 → 409 + 10501 (请求处理中);
 *       无键 (TTL 过期或已释放, SETNX 失败与释放的竞态) → 再占位按新请求</li>
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

    /** 回放标记响应头 (命中首响缓存时附加, 接入方据此区分新响应/回放). */
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    /**
     * 首响缓存 body 上限 (字节). 容量护栏: 防止大响应撑爆 Redis;
     * 超限响应仅保留占位不缓存 (同 key 重复请求 409, 与流式同口径).
     */
    static final int MAX_CACHED_BODY_BYTES = 1024 * 1024;

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
        // body hash 规约 (回归 2026-09-21-01 BL11 P2): 读请求体算 MD5 → 装饰重放给下游 →
        // 占位/回放/缓存全链携带 hash, 同 key 异 body 422 拒绝 (不再回放错误首响)
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bodyBytes -> {
                    String bodyHash = md5Hex(bodyBytes);
                    ServerWebExchange replayableExchange = withReplayableBody(exchange, bodyBytes);
                    return store.tryAcquire(redisKey, ttl, bodyHash)
                            .flatMap(acquired -> acquired
                                    ? firstRequest(replayableExchange, chain, redisKey, bodyHash)
                                    : duplicateRequest(replayableExchange, chain, redisKey, ttl, bodyHash));
                });
    }

    /**
     * 请求体重放装饰 (hash 计算消费了原始 body, 须以缓存字节重建供下游读取).
     */
    private ServerWebExchange withReplayableBody(ServerWebExchange exchange, byte[] bodyBytes) {
        var mutatedRequest = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(
                exchange.getRequest()) {
            @Override
            public reactor.core.publisher.Flux<DataBuffer> getBody() {
                if (bodyBytes.length == 0) {
                    return reactor.core.publisher.Flux.empty();
                }
                return reactor.core.publisher.Flux.defer(() ->
                        reactor.core.publisher.Flux.just(exchange.getResponse().bufferFactory().wrap(bodyBytes)));
            }
        };
        return exchange.mutate().request(mutatedRequest).build();
    }

    /**
     * 首次请求: 放行 (响应经捕获装饰器), 完成后按缓存资格处置.
     * 链路异常 → 释放占位并继续抛出 (失败不占键).
     */
    private Mono<Void> firstRequest(ServerWebExchange exchange, WebFilterChain chain,
                                    String redisKey, String bodyHash) {
        CapturingServerHttpResponse decorated = new CapturingServerHttpResponse(exchange.getResponse());
        ServerWebExchange mutated = exchange.mutate().response(decorated).build();
        return chain.filter(mutated)
                .then(Mono.defer(() -> afterResponse(decorated, redisKey, bodyHash)))
                .onErrorResume(err -> store.release(redisKey).then(Mono.error(err)));
    }

    /**
     * 响应完成处置: 非 2xx → 释放; 2xx 可缓存 → 保存首响 (附 body hash); 流式/超限/无 body → 占位保留.
     */
    private Mono<Void> afterResponse(CapturingServerHttpResponse decorated, String redisKey, String bodyHash) {
        HttpStatusCode status = decorated.getStatusCode();
        if (status == null || !status.is2xxSuccessful()) {
            return store.release(redisKey);
        }
        if (decorated.isCacheable()) {
            MediaType contentType = safeContentType(decorated);
            return store.saveResponse(redisKey, status.value(),
                    contentType == null ? null : contentType.toString(),
                    decorated.getCapturedBody(), bodyHash);
        }
        return Mono.empty();
    }

    /** Content-Type 读取 (畸形头视为未知 → 不可缓存, 不抛异常). */
    private static MediaType safeContentType(ServerHttpResponse response) {
        try {
            return response.getHeaders().getContentType();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 重复请求: body 一致命中首响 → 回放; hash 冲突 → 422; 无响应 → 再占位或 409 处理中.
     * 注意 replay 返回 Mono&lt;Void&gt; (完成即空信号), 故以 thenReturn 物化命中标志.
     */
    private Mono<Void> duplicateRequest(ServerWebExchange exchange, WebFilterChain chain,
                                        String redisKey, Duration ttl, String bodyHash) {
        return store.findResponse(redisKey, bodyHash)
                .flatMap(cached -> replay(exchange, cached).thenReturn(true))
                .defaultIfEmpty(false)
                .flatMap(replayed -> replayed ? Mono.<Void>empty()
                        : Mono.defer(() -> reacquireOrReject(exchange, chain, redisKey, ttl, bodyHash)))
                .onErrorResume(IdempotencyStore.BodyMismatchException.class,
                        mismatch -> rejectBodyMismatch(exchange, mismatch));
    }

    /**
     * 占位无响应: SETNX 失败可能源于「已释放/已过期」竞态 — 再占位成功则按新请求,
     * 失败则确为在途 (或首响不可缓存) → 409 + 10501.
     */
    private Mono<Void> reacquireOrReject(ServerWebExchange exchange, WebFilterChain chain,
                                         String redisKey, Duration ttl, String bodyHash) {
        return store.tryAcquire(redisKey, ttl, bodyHash)
                .flatMap(acquired -> acquired
                        ? firstRequest(exchange, chain, redisKey, bodyHash)
                        : rejectInProgress(exchange))
                .onErrorResume(IdempotencyStore.BodyMismatchException.class,
                        mismatch -> rejectBodyMismatch(exchange, mismatch));
    }

    /** 回放首响: 同 status/contentType/body + Idempotency-Replayed: true. */
    private Mono<Void> replay(ServerWebExchange exchange, IdempotentResponse cached) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(cached.status()));
        if (cached.contentType() != null && !cached.contentType().isBlank()) {
            try {
                response.getHeaders().setContentType(MediaType.parseMediaType(cached.contentType()));
            } catch (Exception e) {
                response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            }
        }
        response.getHeaders().set(REPLAYED_HEADER, "true");
        log.info("[Idempotency] 回放首响: path={}, status={}",
                exchange.getRequest().getPath().value(), cached.status());
        byte[] bytes = cached.body() == null ? new byte[0]
                : cached.body().getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    /** 409 + 10501 (message 区分「请求处理中」, 与换新 key 的旧口径区分). */
    private Mono<Void> rejectInProgress(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.CONFLICT);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = JSON.toJSONString(ApiResponse.fail(ApiCode.DUPLICATE_SUBMIT,
                        "请求处理中或响应不可回放, 请稍后重试或更换 Idempotency-Key"))
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * 422 body 冲突 (body hash 规约): 同 Idempotency-Key 携带不同请求体 —
     * 语义错误 (客户端 bug 或攻击), 不回放不重试.
     */
    private Mono<Void> rejectBodyMismatch(ServerWebExchange exchange,
                                          IdempotencyStore.BodyMismatchException mismatch) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNPROCESSABLE_ENTITY);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        log.warn("[Idempotency] body 冲突拒绝: path={}, detail={}",
                exchange.getRequest().getPath().value(), mismatch.getMessage());
        byte[] bytes = JSON.toJSONString(ApiResponse.fail(ApiCode.PARAM_ERROR,
                        "Idempotency-Key 已被不同请求体使用, 请更换 key 或保持请求体一致"))
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    /** 请求体 MD5 摘要 (hex). */
    private static String md5Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    private static String resolveApiKey(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth != null && !auth.isBlank()) {
            return auth.trim();
        }
        String xApiKey = exchange.getRequest().getHeaders().getFirst("x-api-key");
        return xApiKey != null ? xApiKey.trim() : "anonymous";
    }

    /**
     * 响应捕获装饰器 (回放缓存用): 直通转发, 旁路复制可缓存响应字节.
     *
     * <p>捕获资格 (writeWith 时判定): 2xx + Content-Type 已知且非 text/event-stream +
     * 累计不超 {@value #MAX_CACHED_BODY_BYTES} 字节. writeAndFlushWith (flush-per-chunk =
     * 流式) 一律直通不缓冲; 超限即中止捕获 (已捕获部分丢弃).
     */
    private static final class CapturingServerHttpResponse extends ServerHttpResponseDecorator {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private boolean capturing;
        private boolean overflow;

        private CapturingServerHttpResponse(ServerHttpResponse delegate) {
            super(delegate);
        }

        /** 是否捕获到可缓存 body (2xx 非流式、有 body 且未超限). */
        boolean isCacheable() {
            return capturing && !overflow;
        }

        String getCapturedBody() {
            return captured.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            if (!shouldCapture()) {
                return super.writeWith(body);
            }
            capturing = true;
            // join 后旁路复制 (不移动读位置, 不影响下游写出); SSE 内容类型绝不走此路径
            return super.writeWith(DataBufferUtils.join(body).doOnNext(this::peek));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(body);
        }

        private boolean shouldCapture() {
            HttpStatusCode status = getStatusCode();
            MediaType contentType = safeContentType(this);
            return status != null && status.is2xxSuccessful()
                    && contentType != null
                    && !MediaType.TEXT_EVENT_STREAM.includes(contentType);
        }

        /** 旁路复制字节 (记住读位→读出→复位), 超限即置溢出并丢弃已捕获部分. */
        private void peek(DataBuffer buffer) {
            if (overflow) {
                return;
            }
            if (captured.size() + buffer.readableByteCount() > MAX_CACHED_BODY_BYTES) {
                overflow = true;
                captured.reset();
                return;
            }
            int readPos = buffer.readPosition();
            try {
                byte[] bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                buffer.readPosition(readPos);
                captured.write(bytes);
            } catch (java.io.IOException e) {
                // 旁路复制失败不阻塞响应写出: 放弃本次捕获 (视为不可缓存, 留占位)
                overflow = true;
                captured.reset();
            }
        }
    }
}
