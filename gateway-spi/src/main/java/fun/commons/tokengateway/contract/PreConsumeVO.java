package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreConsumeVO {
    @Builder.Default
    private BigDecimal estimatedQuota = BigDecimal.ZERO;
    private String preConsumeId;
    private boolean success;

    /** 失败原因 (success=false 时填充, 排障用) */
    private String failReason;
}
