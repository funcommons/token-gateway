package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.exception.RelayException;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.relay.AccessLogReporter;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import fun.commons.tokengateway.relay.TokenUsage;
import fun.commons.tokengateway.relay.TokenUsageExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * OpenAI Embeddings 端点 (WebFlux 原生).
 *
 * <p>路径: POST /v1/embeddings
 * <p>Body: { "model": "text-embedding-3-small", "input": "..." | ["a", "b"] }
 * <p>鉴权: Authorization: Bearer 或 x-api-key 双头.
 * <p>无流式; 上游响应原样透传; 结算只取 prompt_tokens (embeddings 无 completion_tokens).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class EmbeddingsController {

    private static final String DEFAULT_MODEL = "text-embedding-3-small";
    private static final String REQUEST_PATH = "/v1/embeddings";
    private static final String UPSTREAM_ENDPOINT = "/v1/embeddings";
    private static final int EST_COMPLETION_TOKENS = 256;

    private final RelayOrchestrator orchestrator;
    private final AccessLogReporter accessLogReporter;
    private final WebClient.Builder webClientBuilder;

    @PostMapping(value = "/v1/embeddings")
    public Mono<ResponseEntity<Object>> embeddings(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestBody Map<String, Object> body
    ) {
        return Mono.deferContextual(cv -> doEmbeddings(authorization, xApiKey, body,
                cv.getOrDefault(fun.commons.tokengateway.trace.TraceWebFilter.CONTEXT_KEY,
                        (String) null)));
    }

    private Mono<ResponseEntity<Object>> doEmbeddings(
            String authorization, String xApiKey, Map<String, Object> body, String traceId) {
        String apiKey = extractApiKey(authorization, xApiKey);
        String model = resolveModel(body);
        int estPromptTokens = estimateInputTokens(body);
        long startNs = System.nanoTime();
        return orchestrator.prepare(apiKey, model, estPromptTokens, EST_COMPLETION_TOKENS, null, traceId)
                .flatMap(prepared -> invokeUpstream(prepared.channel(), body)
                        .doOnNext(resp -> {
                            TokenUsage u = TokenUsageExtractor.fromOpenAi(resp);
                            int latency = elapsedMs(startNs);
                            orchestrator.settle(prepared, u.promptTokens(), 0, 0, latency)
                                    .flatMap(credit -> accessLogReporter.reportSuccess(prepared, model, REQUEST_PATH,
                                            u.promptTokens(), 0, 0, credit, latency, traceId))
                                    .subscribe(
                                            v -> {},
                                            e -> log.warn("[Saga/settle+AccessLog] preConsumeId={}, err={}",
                                                    prepared.preConsumeId(), e.getMessage()));
                        })
                        .map(resp -> ResponseEntity.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .body((Object) resp))
                        .doOnError(err -> {
                            orchestrator.refund(prepared,
                                    "upstream failed: " + err.getMessage()).subscribe(
                                            v -> {},
                                            e -> log.warn("[Saga/refund] preConsumeId={}, err={}",
                                                    prepared.preConsumeId(), e.getMessage()));
                            accessLogReporter.reportError(prepared, model, REQUEST_PATH,
                                    502, elapsedMs(startNs), traceId).subscribe(
                                            v -> {},
                                            e -> log.warn("[AccessLog] err={}", e.getMessage()));
                        }));
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, Object>> invokeUpstream(DistributeVO channel, Map<String, Object> body) {
        String url = channel.getBaseUrl().replaceAll("/+$", "") + UPSTREAM_ENDPOINT;
        return webClientBuilder.build().post()
                .uri(url)
                .header("Authorization", "Bearer " + channel.getApiKey())
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .bodyValue(body)
                .retrieve()
                .bodyToMono((Class<Map<String, Object>>) (Class<?>) Map.class)
                .onErrorResume(e -> {
                    log.error("[Embeddings] 上游调用失败 url={}, err={}", url, e.getMessage());
                    return Mono.error(new RelayException(502, "upstream failed: " + e.getMessage()));
                });
    }

    private static int estimateInputTokens(Map<String, Object> body) {
        Object input = body.get("input");
        if (input instanceof String s) {
            return Math.max(1, s.length() / 4);
        }
        if (input instanceof java.util.List<?> list) {
            int total = 0;
            for (Object o : list) {
                if (o instanceof String s) {
                    total += s.length();
                }
            }
            return Math.max(1, total / 4);
        }
        return 1;
    }

    private static int elapsedMs(long startNs) {
        return (int) ((System.nanoTime() - startNs) / 1_000_000);
    }

    private static String resolveModel(Map<String, Object> body) {
        Object m = body.get("model");
        if (m instanceof String s && !s.isBlank()) {
            return s;
        }
        return DEFAULT_MODEL;
    }

    private static String extractApiKey(String auth, String xApiKey) {
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        return null;
    }
}
