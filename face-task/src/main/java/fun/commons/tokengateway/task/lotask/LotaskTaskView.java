package fun.commons.tokengateway.task.lotask;

import java.util.Map;

/**
 * lotask4j 任务视图 (client 域 GET /api/v1/client/tasks/{id} 的网关侧最小契约).
 *
 * <p>平台 camelCase 契约统一 (V4+); 只取网关状态映射所需字段, 其余字段忽略 (向前兼容).
 *
 * @param id           lotask 任务 ID (OpenID 混淆字符串)
 * @param status       平台状态: PENDING/RUNNING/SUCCESS/FAILED/CANCELLED
 * @param result       终态结果 ({resources[], usage} 契约, Worker resultMapping 钩子保证)
 * @param errorCode    失败码 (FAILED 时, 可空)
 * @param errorMessage 失败信息 (FAILED 时, 可空)
 */
public record LotaskTaskView(
        String id,
        String status,
        Map<String, Object> result,
        String errorCode,
        String errorMessage) {
}
