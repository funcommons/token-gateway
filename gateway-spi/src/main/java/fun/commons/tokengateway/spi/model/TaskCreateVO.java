package fun.commons.tokengateway.spi.model;

/**
 * 任务创建委托响应 (TASK_CREATE 面产物, M0 冻结契约).
 *
 * @param accepted 后端是否受理; false 时网关全额退款并置任务 FAILED
 * @param status   初始状态 (通常 PENDING/RUNNING)
 */
public record TaskCreateVO(boolean accepted, TaskStatus status) {
}
