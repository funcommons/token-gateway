package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.format.FormatConverter;
import fun.commons.tokengateway.relay.RelayOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Anthropic count_tokens 端点 (WebFlux 原生).
 *
 * <p>路径: POST /v1/messages/count_tokens
 * <p>仅 Anthropic 上游支持 (透传); OpenAI 上游 → 501.
 * <p>不计费, 不走 saga.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CountTokensController {

    private static final String COUNT_TOKENS_ENDPOINT = "/v1/messages/count_tokens";

    private final RelayOrchestrator orchestrator;
    private final FormatConverter formatConverter;
    private final WebClient.Builder webClientBuilder;

    @PostMapping("/v1/messages/count_tokens")
    public Mono<Map<String, Object>> countTokens(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestBody Map<String, Object> body
    ) {
        if (body == null || body.isEmpty()) {
            return Mono.error(new RelayException(400,
                    fun.commons.tokengateway.framework.ApiCode.REQUIRED_MISSING.getCode(), "请求体为空"));
        }
        Object modelObj = body.get("model");
        if (!(modelObj instanceof String s) || s.isBlank()) {
            return Mono.error(new RelayException(400,
                    fun.commons.tokengateway.framework.ApiCode.REQUIRED_MISSING.getCode(), "model 字段必填"));
        }
        String apiKey = extractApiKey(authorization, xApiKey);
        String model = (String) modelObj;
        String userContent = RelayOrchestrator.extractUserContent(body);

        return orchestrator.prepare(apiKey, model, 0, 0, userContent, null)
                .flatMap(prepared -> {
                    DistributeVO channel = prepared.channel();
                    if (!"anthropic".equalsIgnoreCase(channel.getProtocol())) {
                        return Mono.<Map<String, Object>>error(new RelayException(501,
                                "count_tokens 仅在 anthropic 协议上游支持, 当前上游: " + channel.getProtocol()));
                    }
                    String url = channel.getBaseUrl().replaceAll("/+$", "") + COUNT_TOKENS_ENDPOINT;
                    return webClientBuilder.build().post()
                            .uri(url)
                            .header("x-api-key", channel.getApiKey())
                            .header("anthropic-version", "2023-06-01")
                            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(Map.class)
                            .map(m -> (Map<String, Object>) m)
                            .onErrorResume(e -> {
                                log.error("[CountTokens] 上游调用失败 url={}, err={}", url, e.getMessage());
                                return Mono.error(new RelayException(502,
                                        "upstream failed: " + e.getMessage()));
                            });
                });
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
