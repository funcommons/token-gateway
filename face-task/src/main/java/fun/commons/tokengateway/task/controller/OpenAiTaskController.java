package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.task.relay.TaskRelayOrchestrator;
import fun.commons.tokengateway.util.ClientIpResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Map;

/**
 * OpenAI 协议任务端点 · videos (issue #19): OpenAI job 形状挂任务引擎, 与 OneToken 协议同级.
 *
 * <p>face=task/all 均注册——LLM 面无 video 路径, 无映射冲突:
 * <ul>
 *   <li>{@code POST /v1/videos} (sora 形状): prompt/seconds/size → video 任务, 返回 video_generation job</li>
 *   <li>{@code GET /v1/videos/{task_no}}: 轮询 job (queued/in_progress/completed/failed)</li>
 *   <li>{@code GET /v1/videos/{task_no}/content}: 完成后 307 至签名代理 URL (视频字节由资源代理承载)</li>
 * </ul>
 * images 的 OpenAI job 端点 (POST/GET /v1/images/generations) 见
 * {@link OpenAiImagesTaskController}——face=task 独占注册, 避免与 LLM 面同路径映射冲突.
 * 鉴权双头 (Bearer 优先) 与信封语义同任务面其余端点.
 */
@RestController
@RequiredArgsConstructor
public class OpenAiTaskController {

    private final TaskRelayOrchestrator orchestrator;
    /** 客户端 IP 解析 (issue #27): token-validate clientIp 填充, 信任代理策略可配. */
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/v1/videos")
    public Mono<Map<String, Object>> createVideo(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> body,
            ServerWebExchange exchange) {
        return orchestrator.createVideoJob(extractApiKey(authorization, xApiKey), body, traceId,
                idempotencyKey, clientIpResolver.resolve(exchange));
    }

    @GetMapping("/v1/videos/{taskNo}")
    public Mono<Map<String, Object>> getVideo(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            ServerWebExchange exchange) {
        return orchestrator.videoJob(taskNo, extractApiKey(authorization, xApiKey),
                clientIpResolver.resolve(exchange));
    }

    @GetMapping("/v1/videos/{taskNo}/content")
    public Mono<ResponseEntity<Void>> videoContent(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            ServerWebExchange exchange) {
        return orchestrator.videoContentUrl(taskNo, extractApiKey(authorization, xApiKey),
                clientIpResolver.resolve(exchange))
                .map(url -> ResponseEntity.status(307).location(URI.create(url)).<Void>build());
    }

    /** Bearer 优先, x-api-key 兜底 (与任务面/LLM 面一致). */
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
