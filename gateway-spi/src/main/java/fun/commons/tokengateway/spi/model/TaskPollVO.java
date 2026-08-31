package fun.commons.tokengateway.spi.model;

import java.util.List;
import java.util.Map;

/**
 * 任务轮询委托响应 (TASK_POLL 面产物, M0 冻结契约).
 *
 * @param status    任务状态 (终态幂等)
 * @param resources 原始资源 URL 列表 (SUCCEEDED 时; 网关资源代理负责转签名代理 URL,
 *                  上游原始 URL 永不透传给调用方)
 * @param usage     用量明细 (按模型而定, 可空)
 * @param error     失败信息 (FAILED/EXPIRED 时)
 */
public record TaskPollVO(
        TaskStatus status,
        List<String> resources,
        Map<String, Object> usage,
        Map<String, Object> error) {
}
