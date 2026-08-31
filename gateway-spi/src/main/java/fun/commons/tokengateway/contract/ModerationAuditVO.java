package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 输出审查结果 (对齐主应用 moderation 模块 AuditResult).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationAuditVO {
    private boolean passed;
    private String actionTaken;
    private String source;
}
