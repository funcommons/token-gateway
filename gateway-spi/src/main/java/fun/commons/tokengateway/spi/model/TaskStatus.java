package fun.commons.tokengateway.spi.model;

/**
 * 任务状态枚举 (任务面状态机, M0 冻结契约; 超 24h 未终态 → EXPIRED + 全额退款).
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    EXPIRED;

    /** 终态 (SUCCEEDED/FAILED/EXPIRED): 轮询幂等不触上游, 不再迁移. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == EXPIRED;
    }
}
