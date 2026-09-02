package fun.commons.tokengateway.task.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.idempotency.IdempotencyStore;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.notify.TerminalEventHandler;
import fun.commons.tokengateway.task.notify.WebhookVerifier;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * lotask4j 终态 webhook 接收 (《05》§8; 平台 outbox 投递, 指数退避重投同 Event-Id).
 *
 * <p>接收规则:
 * <ol>
 *   <li>Event-Id 已见 → 幂等跳过 (重投去重)</li>
 *   <li>三头验签通过 → 直接处理载荷</li>
 *   <li>缺头/验签失败 → <b>不拒收</b>, verify-then-act 回查 lotask4j 核实终态
 *       (平台对无租户归属任务静默降级无签名投递)</li>
 * </ol>
 * 始终 200 应答 (处理失败也 200 + 对账兜底, 避免平台退避风暴打满);
 * 载荷缺 id 等结构性错误才 400 (平台不重投).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LotaskWebhookController {

    /** Event-Id 去重占位 TTL (覆盖 outbox 最大重投周期). */
    private static final Duration EVENT_GUARD_TTL = Duration.ofDays(7);

    private final WebhookVerifier verifier;
    private final IdempotencyStore idempotencyStore;
    private final TaskMetaStore metaStore;
    private final LotaskTaskClient lotaskClient;
    private final TerminalEventHandler terminalEventHandler;

    @PostMapping("/internal/lotask/webhook")
    public Mono<ResponseEntity<Map<String, Object>>> receive(
            @RequestHeader(value = "X-ASTS-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-ASTS-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-ASTS-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        byte[] raw = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        JSONObject payload;
        try {
            payload = JSON.parseObject(rawBody);
        } catch (Exception e) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("received", false, "reason", "invalid json")));
        }
        // 平台 outbox 载荷字段 = task_id (WebhookServiceImpl); 兼容 id 以容契约定型前差异
        String lotaskId = payload == null ? null
                : (payload.getString("task_id") != null ? payload.getString("task_id")
                        : payload.getString("id"));
        String status = payload == null ? null : payload.getString("status");
        if (lotaskId == null || status == null) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("received", false, "reason", "missing id/status")));
        }

        Mono<Boolean> firstSeen = eventId == null ? Mono.just(true)
                : idempotencyStore.tryAcquire("tgw:task:wh:" + eventId, EVENT_GUARD_TTL);
        return firstSeen.flatMap(first -> {
            if (!first) {
                log.info("[Webhook] Event-Id 重投去重跳过: {}", eventId);
                return Mono.just(ok("duplicate"));
            }
            WebhookVerifier.Verdict verdict = verifier.verify(timestamp, signature, raw);
            Mono<Void> handling = verdict == WebhookVerifier.Verdict.VERIFIED
                    ? handleVerified(lotaskId, status, payload)
                    : handleUnverified(lotaskId, eventId);
            return handling
                    .onErrorResume(e -> {
                        log.error("[Webhook] 处理失败 (对账兜底补偿): lotaskId={}, err={}",
                                lotaskId, e.getMessage());
                        return Mono.empty();
                    })
                    .thenReturn(ok(verdict == WebhookVerifier.Verdict.VERIFIED
                            ? "verified" : "reconciled"));
        });
    }

    /** 验签通过: 直接按载荷处理终态 (result 取载荷子对象). */
    private Mono<Void> handleVerified(String lotaskId, String status, JSONObject payload) {
        return metaStore.findTaskNo(lotaskId)
                .flatMap(taskNo -> metaStore.getMeta(taskNo)
                        .flatMap(meta -> terminalEventHandler.onTerminal(
                                taskNo, meta, status, payload.getJSONObject("result"))))
                .doOnSuccess(v -> log.info("[Webhook] 验签通过已处理: lotaskId={}", lotaskId));
    }

    /** 验签失败/无签名: verify-then-act 回查平台核实终态后再处理. */
    private Mono<Void> handleUnverified(String lotaskId, String eventId) {
        log.warn("[Webhook] 无签名/验签失败, 回查核实: lotaskId={}, eventId={}", lotaskId, eventId);
        return lotaskClient.get(lotaskId)
                .flatMap(view -> metaStore.findTaskNo(lotaskId)
                        .flatMap(taskNo -> metaStore.getMeta(taskNo)
                                .flatMap(meta -> terminalEventHandler.onTerminalView(
                                        taskNo, meta, view))))
                .onErrorResume(e -> {
                    log.error("[Webhook] 回查失败: lotaskId={}, err={}", lotaskId, e.getMessage());
                    return Mono.empty();
                });
    }

    private static ResponseEntity<Map<String, Object>> ok(String mode) {
        return ResponseEntity.ok(Map.of("received", true, "mode", mode));
    }
}
