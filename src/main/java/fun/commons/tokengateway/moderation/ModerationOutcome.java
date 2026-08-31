package fun.commons.tokengateway.moderation;

import java.util.List;

/**
 * Moderation 网关层结果封装 (webflux 版).
 *
 * <p>屏蔽 {@link ScanResult} 细节, 上层只需关心 3 个动作: pass-through / mask-content / block-request.
 */
public record ModerationOutcome(Action action, String sanitizedContent, List<String> ruleCodes) {

    public enum Action {
        /** 内容通过, body 不变 */
        PASS_THROUGH,
        /** 内容脱敏, 用 sanitizedContent 替换最后一条 user message */
        MASK_CONTENT,
        /** 拒绝请求, 抛 RelayException(400) */
        BLOCK_REQUEST
    }

    public static ModerationOutcome pass(String sanitized) {
        return new ModerationOutcome(Action.PASS_THROUGH, sanitized, List.of());
    }

    public static ModerationOutcome mask(String sanitized) {
        return new ModerationOutcome(Action.MASK_CONTENT, sanitized, List.of());
    }

    public static ModerationOutcome block(List<String> ruleCodes) {
        return new ModerationOutcome(Action.BLOCK_REQUEST, null, ruleCodes);
    }

    public boolean isBlocked() {
        return action == Action.BLOCK_REQUEST;
    }
}
