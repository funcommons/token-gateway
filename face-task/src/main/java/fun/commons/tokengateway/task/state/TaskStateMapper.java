package fun.commons.tokengateway.task.state;

import fun.commons.tokengateway.spi.model.TaskStatus;

/**
 * lotask4j 状态 → 网关五态映射 (《05》§6).
 *
 * <p>SUCCESS→SUCCEEDED; FAILED/CANCELLED→FAILED (运营取消并入, 全额退款);
 * PENDING/RUNNING 直映; 未知态保守归 PENDING (非终态, 不触发退款, 等超时钟裁定).
 * EXPIRED 不由平台状态映射 (lotask4j 超时混在 FAILED 无独立 error_code),
 * 由网关超时钟判定 (《05》§10 R6 网关侧补偿, M2.5c TimeoutClockJob).
 */
public final class TaskStateMapper {

    private TaskStateMapper() {
    }

    public static TaskStatus map(String lotaskStatus) {
        if (lotaskStatus == null) {
            return TaskStatus.PENDING;
        }
        return switch (lotaskStatus) {
            case "SUCCESS" -> TaskStatus.SUCCEEDED;
            case "FAILED", "CANCELLED" -> TaskStatus.FAILED;
            case "RUNNING" -> TaskStatus.RUNNING;
            default -> TaskStatus.PENDING;
        };
    }
}
