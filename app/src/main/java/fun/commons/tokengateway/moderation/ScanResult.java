package fun.commons.tokengateway.moderation;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Prompt scan 响应 (webflux 本地 DTO, 镜像主应用 moderation 的 ScanResult 字段).
 */
@Getter
@Setter
@ToString
public class ScanResult {

    private boolean passed;

    private List<Match> matches;

    private String sanitizedContent;

    private String actionTaken;

    @Getter
    @Setter
    @ToString
    public static class Match {

        private String ruleCode;

        private String matchedText;

        private int severity;
    }
}
