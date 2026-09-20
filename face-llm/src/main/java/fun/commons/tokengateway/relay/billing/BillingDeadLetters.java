package fun.commons.tokengateway.relay.billing;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结算死信结构化日志 (issue #23 死信形态: 不落库, 单行 JSON 快照供人工捞取重放).
 *
 * <p>触发: 业务拒绝 (信封 code!=0, 重试同参数无意义) / pending 重放次数耗尽 /
 * pending 入队失败 (Redis 不可用兜底). 捞取方式: grep {@code [Billing-DeadLetter]},
 * 快照内含全部结算参数, 可按 preConsumeId 幂等重放至能力面.
 */
@Slf4j
public final class BillingDeadLetters {

    private BillingDeadLetters() {
    }

    /**
     * 记一条死信快照 (ERROR 单行 JSON): event/phase/retry 上下文 + 记录全字段.
     *
     * @param record 待重放记录快照 (业务拒绝场景为入队前构造, retries=0)
     * @param phase  死信发生环节: settle / refund / enqueue / reconcile
     * @param reason 死信原因 (信封 code/message 等; 强制单行化)
     */
    public static void log(BillingPendingRecord record, String phase, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "billing-dead-letter");
        payload.put("phase", phase);
        payload.put("reason", oneLine(reason));
        payload.put("retries", record == null ? null : record.retries());
        payload.put("record", record);
        log.error("[Billing-DeadLetter] {}", JSON.toJSONString(payload));
    }

    /** 换行压平, 保证死信快照严格单行 (可 grep 可解析). */
    private static String oneLine(String s) {
        return s == null ? null : s.replaceAll("[\\r\\n]+", " ");
    }
}
