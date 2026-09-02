package fun.commons.tokengateway.demo;

import fun.commons.tokengateway.thmp.ThmpSignature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * notify 回调接收端 (消费端角色, 《05》§5.2): 任务面终态回调的演练靶.
 *
 * <p>验签 X-THMP-Signature = Base64(HmacSHA256(notifySignKey, rawBody));
 * 按 task_no 幂等去重; 收到的回调落内存清单, 冒烟脚本经 GET /demo/notifications 断言
 * (如 "先退款后回调" 顺序: 回调到达时 /demo/state 里对应预扣已 REFUNDED).
 */
@Slf4j
@RestController
public class NotifyReceiverController {

    @Value("${demo.notify-sign-key:}")
    private String notifySignKey;

    private final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> seenTaskNos = new ConcurrentHashMap<>();

    @PostMapping("/callback")
    public Map<String, Object> callback(
            @RequestHeader(value = "X-THMP-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        byte[] raw = rawBody == null ? new byte[0] : rawBody.getBytes(StandardCharsets.UTF_8);
        boolean verified = false;
        if (notifySignKey != null && !notifySignKey.isBlank() && signature != null) {
            String expected = ThmpSignature.sign(notifySignKey,
                    new String(raw, StandardCharsets.UTF_8));
            verified = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        }
        com.alibaba.fastjson2.JSONObject body = com.alibaba.fastjson2.JSON.parseObject(rawBody);
        String taskNo = body == null ? null : body.getString("task_no");
        boolean duplicate = taskNo != null && seenTaskNos.putIfAbsent(taskNo, true) != null;
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("task_no", taskNo);
        record.put("status", body == null ? null : body.getString("status"));
        record.put("verified", verified);
        record.put("duplicate", duplicate);
        received.add(record);
        log.info("[NotifyDemo] 收到回调: taskNo={}, status={}, verified={}, dup={}",
                taskNo, record.get("status"), verified, duplicate);
        return Map.of("received", true, "verified", verified, "duplicate", duplicate);
    }

    @org.springframework.web.bind.annotation.GetMapping("/demo/notifications")
    public List<Map<String, Object>> notifications() {
        return List.copyOf(received);
    }
}
