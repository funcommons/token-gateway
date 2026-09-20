package fun.commons.tokengateway.relay.billing;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.contract.SettleRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待重放记录单测 (issue #23): 两型工厂 / 重试计数递增 / settle 请求体还原
 * / JSON 单行 round-trip (ZSET member 的序列化契约).
 */
@DisplayName("BillingPendingRecord")
class BillingPendingRecordTest {

    private static List<SettleRequest.AttemptDetail> attempts() {
        return List.of(SettleRequest.AttemptDetail.builder()
                .sequence(1).channelId("c1").model("gpt-4o-mini")
                .errorClass("HTTP_502").billed(true)
                .promptTokens(10).completionTokens(0)
                .build());
    }

    @Test
    @DisplayName("settle 型工厂: attempts 空表归一 null; withRetries 只动计数, 结算参数原样")
    void settleFactoryAndWithRetries() {
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-1", "req-1", 7L, 100, 20, 30, 5, 8L, null, 1234, attempts());
        assertThat(record.type()).isEqualTo("settle");
        assertThat(record.isRefund()).isFalse();
        assertThat(record.attempts()).hasSize(1);
        assertThat(record.retries()).isZero();

        BillingPendingRecord next = record.withRetries(3);
        assertThat(next.retries()).isEqualTo(3);
        assertThat(next.preConsumeId()).isEqualTo("pc-1");
        assertThat(next.requestId()).isEqualTo("req-1");
        assertThat(next.ownerPartyId()).isEqualTo(7L);
        assertThat(next.actualPromptTokens()).isEqualTo(100);
        assertThat(next.actualCompletionTokens()).isEqualTo(20);
        assertThat(next.cacheReadTokens()).isEqualTo(30);
        assertThat(next.cacheCreationTokens()).isEqualTo(5);
        assertThat(next.reasoningTokens()).isEqualTo(8L);
        assertThat(next.audioTokens()).isNull();
        assertThat(next.responseTimeMs()).isEqualTo(1234);
        assertThat(next.attempts()).isSameAs(record.attempts());

        assertThat(BillingPendingRecord.forSettle(
                "pc-1", "req-1", null, 1, 2, 3, 4, null, null, 0, List.of())
                .attempts()).isNull();
    }

    @Test
    @DisplayName("refund 型工厂: 仅 preConsumeId/requestId/refundReason 有值")
    void refundFactory() {
        BillingPendingRecord record = BillingPendingRecord.forRefund("pc-2", "req-2", "client cancelled");
        assertThat(record.isRefund()).isTrue();
        assertThat(record.preConsumeId()).isEqualTo("pc-2");
        assertThat(record.refundReason()).isEqualTo("client cancelled");
        assertThat(record.actualPromptTokens()).isNull();
        assertThat(record.attempts()).isNull();
        assertThat(record.ownerPartyId()).isNull();
    }

    @Test
    @DisplayName("还原 settle 请求体: 与原始入队参数逐字段一致 (幂等锚 preConsumeId 不变)")
    void toSettleRequestRebuildsOriginalParams() {
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-1", "req-1", 7L, 100, 20, 30, 5, 8L, 9L, 1234, attempts());
        SettleRequest request = record.toSettleRequest();
        assertThat(request.getPreConsumeId()).isEqualTo("pc-1");
        assertThat(request.getRequestId()).isEqualTo("req-1");
        assertThat(request.getOwnerPartyId()).isEqualTo(7L);
        assertThat(request.getActualPromptTokens()).isEqualTo(100);
        assertThat(request.getActualCompletionTokens()).isEqualTo(20);
        assertThat(request.getCacheReadTokens()).isEqualTo(30);
        assertThat(request.getCacheCreationTokens()).isEqualTo(5);
        assertThat(request.getReasoningTokens()).isEqualTo(8L);
        assertThat(request.getAudioTokens()).isEqualTo(9L);
        assertThat(request.isSuccess()).isTrue();
        assertThat(request.getResponseTimeMs()).isEqualTo(1234);
        assertThat(request.getAttempts()).hasSize(1);
        SettleRequest.AttemptDetail detail = request.getAttempts().get(0);
        assertThat(detail.getSequence()).isEqualTo(1);
        assertThat(detail.getChannelId()).isEqualTo("c1");
        assertThat(detail.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(detail.getErrorClass()).isEqualTo("HTTP_502");
        assertThat(detail.isBilled()).isTrue();
        assertThat(detail.getPromptTokens()).isEqualTo(10);
        assertThat(detail.getCompletionTokens()).isZero();

        // attempts 为 null (无明细语义) → 请求体同样不携带明细
        SettleRequest noAttempts = BillingPendingRecord.forSettle(
                "pc-1", "req-1", null, 1, 2, 3, 4, null, null, 0, null).toSettleRequest();
        assertThat(noAttempts.getAttempts()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("JSON round-trip: record 序列化为单行 member, 反解析字段无损")
    void jsonRoundTrip() {
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-1", "req-1", 7L, 100, 20, 30, 5, 8L, null, 1234, attempts()).withRetries(2);
        String json = JSON.toJSONString(record);
        assertThat(json).doesNotContain("\n").doesNotContain("\r");
        assertThat(json).contains("\"preConsumeId\":\"pc-1\"");

        BillingPendingRecord parsed = JSON.parseObject(json, BillingPendingRecord.class);
        assertThat(parsed).isEqualTo(record);
    }
}
