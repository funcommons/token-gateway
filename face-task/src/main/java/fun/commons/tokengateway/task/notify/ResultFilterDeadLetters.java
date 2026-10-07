package fun.commons.tokengateway.task.notify;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * result-filter 死信结构化日志 (issue #43; 形态对齐 face-llm BillingDeadLetters:
 * 不落库, 单行 JSON 快照供人工捞取).
 *
 * <p>触发: 改写 RPC 失败/超时/信封 code≠0 且尝试计数超过 max-attempts ——
 * 此后 fail-open 落原值 (不卡死任务), 消费方如需改写以死信快照人工补偿.
 * 捞取方式: grep {@code [ResultFilter-DeadLetter]}; 快照含 taskNo/result 原值/错误.
 */
@Slf4j
public final class ResultFilterDeadLetters {

    private ResultFilterDeadLetters() {
    }

    /**
     * 记一条死信快照 (ERROR 单行 JSON).
     *
     * @param taskNo         网关任务号
     * @param originalResult 原始 result (上游裸 URL 原值, 单行化进快照)
     * @param attempts       已尝试次数 (INCR 后值)
     * @param reason         失败原因 (信封 code/message 或 RPC 异常; 强制单行化)
     */
    public static void log(String taskNo, Map<String, Object> originalResult,
                           long attempts, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "result-filter-dead-letter");
        payload.put("taskNo", taskNo);
        payload.put("attempts", attempts);
        payload.put("reason", oneLine(reason));
        payload.put("result", originalResult);
        log.error("[ResultFilter-DeadLetter] {}", JSON.toJSONString(payload));
    }

    /** 换行压平, 保证死信快照严格单行 (可 grep 可解析). */
    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n]+", " ");
    }
}
