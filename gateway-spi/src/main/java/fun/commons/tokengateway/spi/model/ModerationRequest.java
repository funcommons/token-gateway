package fun.commons.tokengateway.spi.model;

/**
 * 审核扫描请求 (MODERATION_SCAN 面入参, M0 冻结契约).
 */
public record ModerationRequest(
        String content,
        String contentType,
        String tenantId,
        String userId,
        Direction direction) {

    public enum Direction {
        /** 输入扫描 (转发前). */
        INPUT,
        /** 输出扫描 (响应审核, 违规不回款已 settle 部分). */
        OUTPUT
    }
}
