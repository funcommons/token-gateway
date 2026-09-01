package fun.commons.tokengateway.worker.lotask;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Worker 抢占到的任务 (lotask4j worker 域 PollTaskResponse 最小契约).
 *
 * <p>fencing: 上报进度/结果必须回传 executionToken + version (平台 CAS 校验,
 * Worker 切换后老 token 上报被拒); leaseExpireAt 前须 progress 续约或完成上报.
 */
public record ClaimedTask(
        String id,
        String type,
        Map<String, Object> payload,
        Long executionToken,
        Integer version,
        Integer attempt,
        OffsetDateTime leaseExpireAt) {
}
