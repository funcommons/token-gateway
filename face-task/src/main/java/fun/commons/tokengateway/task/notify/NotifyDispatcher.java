package fun.commons.tokengateway.task.notify;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 调用方终态回调 (《05》§5.2: X-THMP-Signature + 退避重发, 先退款后 notify).
 *
 * <p>签名: X-THMP-Signature = Base64(HmacSHA256(notifySignKey, rawBody)),
 * 密钥环境变量注入 (task.notify-sign-key); 调用方以此验签 (任务面手册 §4).
 * 退避: notify-retry 档位 (默认 1m/10m/1h), 穷尽后仅告警 (对账兜底可再触发终态处理).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyDispatcher {

    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;

    /**
     * 终态回调 (fire-and-forget: 内部自订阅退避链, 不入调用方 Reactor 主链).
     */
    public void dispatch(String taskNo, String notifyUrl, Map<String, Object> body) {
        if (notifyUrl == null || notifyUrl.isBlank()) {
            return;
        }
        List<Duration> retries = props.getTask().getNotifyRetry();
        attempt(taskNo, notifyUrl, body, retries, 0)
                .subscribe(null, e -> log.error("[Notify] 回调异常终断: taskNo={}, err={}",
                        taskNo, e.getMessage()));
    }

    private Mono<Void> attempt(String taskNo, String notifyUrl, Map<String, Object> body,
                               List<Duration> retries, int attemptIdx) {
        String raw = JSON.toJSONString(body);
        WebClient.RequestBodySpec spec = webClientBuilder.build().post()
                .uri(notifyUrl)
                .contentType(MediaType.APPLICATION_JSON);
        String signKey = props.getTask().getNotifySignKey();
        if (signKey != null && !signKey.isBlank()) {
            spec.header("X-THMP-Signature", ThmpSignature.sign(signKey, raw));
        }
        return spec.bodyValue(raw)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(resp -> log.info("[Notify] 回调成功: taskNo={}, attempt={}", taskNo, attemptIdx))
                .then()
                .onErrorResume(e -> {
                    if (attemptIdx >= retries.size()) {
                        log.error("[Notify] 回调退避穷尽, 放弃: taskNo={}, attempts={}, err={}",
                                taskNo, attemptIdx, e.getMessage());
                        return Mono.empty();
                    }
                    Duration delay = retries.get(attemptIdx);
                    log.warn("[Notify] 回调失败, {} 后第 {} 次重发: taskNo={}, err={}",
                            delay, attemptIdx + 1, taskNo, e.getMessage());
                    return Mono.delay(delay).then(attempt(taskNo, notifyUrl, body, retries, attemptIdx + 1));
                });
    }
}
