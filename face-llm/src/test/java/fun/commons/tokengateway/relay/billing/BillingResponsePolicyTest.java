package fun.commons.tokengateway.relay.billing;

import fun.commons.tokengateway.contract.SettleVO;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * settle/refund 响应三分类策略单测 (issue #23):
 * 基础设施折叠码 10003 / code=0 载荷不完整 = 可重试; 其余非零业务码 = 不重试.
 */
@DisplayName("BillingResponsePolicy")
class BillingResponsePolicyTest {

    @Test
    @DisplayName("无响应 / 10003 折叠码 (RPC 异常·超时·5xx) → 可重试基础设施失败")
    void infraFailuresAreRetryable() {
        assertThat(BillingResponsePolicy.retryableInfra(null)).isTrue();
        assertThat(BillingResponsePolicy.retryableInfra(
                ApiResponse.fail(ApiCode.SERVICE_TIMEOUT))).isTrue();
        assertThat(BillingResponsePolicy.retryableInfra(
                ApiResponse.fail(10003, "billing settle RPC failed: timeout"))).isTrue();
    }

    @Test
    @DisplayName("code=0 但载荷不完整 (settle 无 creditConsumed) → 可重试 (漏发才是资损)")
    void successEnvelopeWithIncompletePayloadIsRetryable() {
        ApiResponse<SettleVO> resp = ApiResponse.success(null);
        assertThat(BillingResponsePolicy.retryableInfra(resp)).isTrue();
        assertThat(BillingResponsePolicy.businessRejected(resp)).isFalse();
    }

    @Test
    @DisplayName("业务信封 code!=0 且非 10003 → 业务拒绝 (重试同参数无意义)")
    void businessCodesAreNotRetryable() {
        assertThat(BillingResponsePolicy.businessRejected(
                ApiResponse.fail(10617, "用户算力余额不足"))).isTrue();
        assertThat(BillingResponsePolicy.businessRejected(
                ApiResponse.fail(10400, "预扣单不存在"))).isTrue();
        assertThat(BillingResponsePolicy.retryableInfra(
                ApiResponse.fail(10617, "用户算力余额不足"))).isFalse();
    }

    @Test
    @DisplayName("确认成功形态 (code=0 + creditConsumed) 既非可重试也非业务拒绝")
    void confirmedSuccessIsNeither() {
        ApiResponse<SettleVO> resp = ApiResponse.success(
                SettleVO.builder().creditConsumed(new BigDecimal("0.5")).build());
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().getCreditConsumed()).isEqualByComparingTo("0.5");
        assertThat(BillingResponsePolicy.retryableInfra(resp)).isTrue();
        assertThat(BillingResponsePolicy.businessRejected(resp)).isFalse();
    }
}
