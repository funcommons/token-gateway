package fun.commons.tokengateway.spi.model;

import java.util.List;

/**
 * 审核扫描结果 (MODERATION_SCAN 面产物, M0 冻结契约).
 *
 * <p>语义对齐 moderation-fail-open-behavior: PASS 放行 / BLOCK 拦截 / SANITIZE 脱敏后放行.
 */
public record ScanResult(
        Action action,
        String sanitizedContent,
        String reason,
        List<String> ruleCodes) {

    public enum Action {
        /** 放行 (含审核依赖故障时 fail-open 的放行). */
        PASS,
        /** 拦截 (管线回 400 + 10106 语义). */
        BLOCK,
        /** 脱敏后放行 (管线用 sanitizedContent 改写输入). */
        SANITIZE
    }

    public static ScanResult pass() {
        return new ScanResult(Action.PASS, null, null, List.of());
    }
}
