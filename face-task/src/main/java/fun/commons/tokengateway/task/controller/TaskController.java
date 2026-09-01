package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.task.relay.TaskRelayOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 任务面四模态端点 (契约 = docs/用户文档/04_任务面API契约.yaml, M2.5a).
 *
 * <p>create: POST /v1/{videos|images|audios|tts} → {task_no, status, poll_url};
 * poll: GET /v1/{videos|images|audios|tts}/{task_no} → {task_no, status, result?, error?}.
 * 鉴权双头 (Bearer 优先, 同 LLM 面); 信封/错误码经 GlobalExceptionHandler 统一 (ApiCode 业务码).
 *
 * <p>M2.5a 范围: create/poll; 资源代理 /v1/resources/** 与 notify/webhook 为 M2.5c.
 */
@RestController
@RequiredArgsConstructor
public class TaskController {

    private final TaskRelayOrchestrator orchestrator;

    @PostMapping("/v1/videos")
    public Mono<Map<String, Object>> createVideo(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody Map<String, Object> body) {
        return orchestrator.create("video", extractApiKey(authorization, xApiKey), body, traceId);
    }

    @PostMapping("/v1/images")
    public Mono<Map<String, Object>> createImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody Map<String, Object> body) {
        return orchestrator.create("image", extractApiKey(authorization, xApiKey), body, traceId);
    }

    @PostMapping("/v1/audios")
    public Mono<Map<String, Object>> createAudio(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody Map<String, Object> body) {
        return orchestrator.create("audio", extractApiKey(authorization, xApiKey), body, traceId);
    }

    @PostMapping("/v1/tts")
    public Mono<Map<String, Object>> createTts(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
            @RequestBody Map<String, Object> body) {
        return orchestrator.create("tts", extractApiKey(authorization, xApiKey), body, traceId);
    }

    @GetMapping("/v1/videos/{taskNo}")
    public Mono<Map<String, Object>> pollVideo(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey) {
        return orchestrator.poll("video", taskNo, extractApiKey(authorization, xApiKey));
    }

    @GetMapping("/v1/images/{taskNo}")
    public Mono<Map<String, Object>> pollImage(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey) {
        return orchestrator.poll("image", taskNo, extractApiKey(authorization, xApiKey));
    }

    @GetMapping("/v1/audios/{taskNo}")
    public Mono<Map<String, Object>> pollAudio(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey) {
        return orchestrator.poll("audio", taskNo, extractApiKey(authorization, xApiKey));
    }

    @GetMapping("/v1/tts/{taskNo}")
    public Mono<Map<String, Object>> pollTts(
            @PathVariable String taskNo,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey) {
        return orchestrator.poll("tts", taskNo, extractApiKey(authorization, xApiKey));
    }

    /** Bearer 优先, x-api-key 兜底 (与 LLM 面一致). */
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
