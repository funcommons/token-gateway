package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.task.relay.TaskRelayOrchestrator;
import fun.commons.tokengateway.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * OpenAI 协议任务端点 · images (issue #19): face=task 独占注册.
 *
 * <p>face=all 时 {@code POST /v1/images/generations} 由 LLM 面透传持有 (同路径映射冲突),
 * OpenAI 协议异步生图须 face=task 部署 (或改用 OneToken 协议); face=task 下本控制器使
 * OpenAI SDK 完整可用:
 * <ul>
 *   <li>{@code POST /v1/images/generations} + {@code background:true} → image_generation job
 *       (异步); 缺省 background → 同步封装 (create + 轮询至终态, 60s 超时降级 PROCESSING)</li>
 *   <li>{@code GET /v1/images/generations/{task_no}}: 轮询 job (completed 带 output 代理 URL)</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "token-gateway", name = "face", havingValue = "task")
public class OpenAiImagesTaskController {

    private final TaskRelayOrchestrator orchestrator;
    /** 客户端 IP 解析 (issue #27): token-validate clientIp 填充, 信任代理策略可配. */
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/v1/images/generations")
    public Mono<Map<String, Object>> createImages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        if (isBackground(body)) {
            return orchestrator.createImageJob(extractApiKey(authorization, xApiKey), body, traceId,
                    idempotencyKey, clientIpResolver.resolve(exchange));
        }
        return orchestrator.createImageGenerations(
                extractApiKey(authorization, xApiKey), body, traceId, idempotencyKey,
                clientIpResolver.resolve(exchange));
    }

    @GetMapping("/v1/images/generations/{taskNo}")
    public Mono<Map<String, Object>> getImageJob(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            ServerWebExchange exchange) {
        return orchestrator.imageJob(taskNo, extractApiKey(authorization, xApiKey),
                clientIpResolver.resolve(exchange));
    }

    private static boolean isBackground(Map<String, Object> body) {
        Object b = body == null ? null : body.get("background");
        if (b instanceof Boolean bool) {
            return bool;
        }
        return b instanceof String s && "true".equalsIgnoreCase(s);
    }

    private static String extractApiKey(String authorization, String xApiKey) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String key = authorization.substring(7).trim();
            return key.isEmpty() ? null : key;
        }
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        return null;
    }
}
