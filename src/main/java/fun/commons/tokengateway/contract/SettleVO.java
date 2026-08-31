package fun.commons.tokengateway.contract;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 结算响应 (从主应用 settle RPC 返回, 含实际消费金额).
 *
 * <p>供 gateway-webflux 填充 access_log.creditConsumed.
 *
 * @author system
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettleVO {

    /** 实际消费金额 (NUMERIC(18,4)) */
    private BigDecimal creditConsumed;
}
