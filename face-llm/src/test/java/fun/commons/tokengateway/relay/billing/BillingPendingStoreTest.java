package fun.commons.tokengateway.relay.billing;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.contract.SettleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BillingPendingStore 单测 (mock ReactiveStringRedisTemplate, 参照任务面 store 风格):
 * 入队 zadd (score=下次重试时刻) / 到期取分页 / 重排先 zadd 新再 zrem 旧 /
 * 入队失败死信兜底 / 坏成员毒丸出队.
 */
@DisplayName("BillingPendingStore")
class BillingPendingStoreTest {

    private ReactiveZSetOperations<String, String> zsetOps;
    private BillingReconcileProperties properties;
    private BillingPendingStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        zsetOps = (ReactiveZSetOperations<String, String>) mock(ReactiveZSetOperations.class);
        Mockito.when(template.opsForZSet()).thenReturn(zsetOps);
        properties = new BillingReconcileProperties();
        store = new BillingPendingStore(template, properties);
    }

    @Test
    @DisplayName("入队: member = 记录 JSON 单行, score = now + base-backoff, retries=0")
    void enqueueWritesZaddWithBackoffScore() {
        Mockito.when(zsetOps.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.just(true));
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-1", "req-1", 7L, 100, 20, 30, 5, null, null, 1234, null,
                fun.commons.tokengateway.contract.SettleRequest.USAGE_SOURCE_UPSTREAM);

        long before = System.currentTimeMillis();
        StepVerifier.create(store.enqueue(record)).expectNext(true).verifyComplete();
        long after = System.currentTimeMillis();

        ArgumentCaptor<String> member = ArgumentCaptor.forClass(String.class);
        long base = properties.getBaseBackoff().toMillis();
        verify(zsetOps).add(eq(BillingPendingStore.PENDING_ZSET), member.capture(),
                Mockito.doubleThat(score -> score >= before + base && score <= after + base));
        BillingPendingRecord parsed = JSON.parseObject(member.getValue(),
                BillingPendingRecord.class);
        assertThat(parsed.type()).isEqualTo("settle");
        assertThat(parsed.preConsumeId()).isEqualTo("pc-1");
        assertThat(parsed.requestId()).isEqualTo("req-1");
        assertThat(parsed.ownerPartyId()).isEqualTo(7L);
        assertThat(parsed.actualPromptTokens()).isEqualTo(100);
        assertThat(parsed.actualCompletionTokens()).isEqualTo(20);
        assertThat(parsed.cacheReadTokens()).isEqualTo(30);
        assertThat(parsed.cacheCreationTokens()).isEqualTo(5);
        assertThat(parsed.responseTimeMs()).isEqualTo(1234);
        assertThat(parsed.usageSource()).isEqualTo(
                fun.commons.tokengateway.contract.SettleRequest.USAGE_SOURCE_UPSTREAM);
        assertThat(parsed.retries()).isZero();
    }

    @Test
    @DisplayName("入队失败 (Redis 不可用) → 死信快照兜底, 返回 false 不向上抛")
    void enqueueFailureFallsBackToDeadLetter() {
        Mockito.when(zsetOps.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));
        BillingPendingRecord record = BillingPendingRecord.forRefund("pc-2", "req-2", "client cancelled");

        StepVerifier.create(store.enqueue(record)).expectNext(false).verifyComplete();
    }

    @Test
    @DisplayName("到期扫描: score∈[0,now] 分页取回并解析; 坏成员死信出队不阻塞好成员")
    void duePendingParsesAndEvictsCorruptMembers() {
        String good = JSON.toJSONString(BillingPendingRecord.forRefund("pc-3", "req-3", "upstream failed"));
        Mockito.when(zsetOps.rangeByScore(Mockito.anyString(), Mockito.any(),
                        Mockito.any(org.springframework.data.redis.connection.Limit.class)))
                .thenReturn(Flux.just(good, "{not-json"));
        Mockito.when(zsetOps.remove(eq(BillingPendingStore.PENDING_ZSET), anyString()))
                .thenReturn(Mono.just(1L));

        List<BillingPendingStore.BillingPendingEntry> entries = store.duePending(50)
                .collectList()
                .block();
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).record().preConsumeId()).isEqualTo("pc-3");
        assertThat(entries.get(0).member()).isEqualTo(good);
        // 毒丸 member 出队, 不再常驻
        verify(zsetOps).remove(BillingPendingStore.PENDING_ZSET, "{not-json");
    }

    @Test
    @DisplayName("扫描失败 (Redis 不可用) → 空流, 下轮再试")
    void duePendingSwallowsScanError() {
        Mockito.when(zsetOps.rangeByScore(Mockito.anyString(), Mockito.any(),
                        Mockito.any(org.springframework.data.redis.connection.Limit.class)))
                .thenReturn(Flux.error(new IllegalStateException("redis down")));
        StepVerifier.create(store.duePending(50)).verifyComplete();
    }

    @Test
    @DisplayName("出队: zrem 按原始 member 字节")
    void removeByRawMember() {
        BillingPendingStore.BillingPendingEntry entry = new BillingPendingStore.BillingPendingEntry(
                BillingPendingRecord.forRefund("pc-4", "req-4", "r"), "raw-member-json");
        Mockito.when(zsetOps.remove(anyString(), anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(store.remove(entry)).expectNext(1L).verifyComplete();
        verify(zsetOps).remove(BillingPendingStore.PENDING_ZSET, "raw-member-json");
    }

    @Test
    @DisplayName("重排: 先 zadd 新成员 (retries+1, 新 score) 再 zrem 旧成员 (顺序防丢)")
    void rescheduleAddsNextThenRemovesOld() {
        BillingPendingRecord old = BillingPendingRecord.forSettle(
                "pc-5", "req-5", null, 10, 2, 0, 0, null, null, 100, null, null);
        BillingPendingRecord next = old.withRetries(1);
        BillingPendingStore.BillingPendingEntry entry = new BillingPendingStore.BillingPendingEntry(
                old, JSON.toJSONString(old));
        Mockito.when(zsetOps.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.just(true));
        Mockito.when(zsetOps.remove(anyString(), anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(store.reschedule(entry, next, 1234567890L))
                .expectNext(true)
                .verifyComplete();

        InOrder inOrder = Mockito.inOrder(zsetOps);
        ArgumentCaptor<String> member = ArgumentCaptor.forClass(String.class);
        inOrder.verify(zsetOps).add(eq(BillingPendingStore.PENDING_ZSET), member.capture(),
                eq(1234567890.0));
        inOrder.verify(zsetOps).remove(BillingPendingStore.PENDING_ZSET, entry.member());
        assertThat(member.getValue()).contains("\"retries\":1");
        assertThat(member.getValue()).isNotEqualTo(entry.member());
    }

    @Test
    @DisplayName("settle 型 member 序列化保留 attempts 明细 (round-trip 无损)")
    void memberKeepsAttemptDetails() {
        Mockito.when(zsetOps.add(anyString(), anyString(), anyDouble()))
                .thenReturn(Mono.just(true));
        BillingPendingRecord record = BillingPendingRecord.forSettle(
                "pc-6", "req-6", null, 10, 2, 0, 0, null, null, 100,
                List.of(SettleRequest.AttemptDetail.builder()
                        .sequence(1).channelId("c9").model("m9")
                        .errorClass("HTTP_500").billed(true)
                        .promptTokens(3).completionTokens(1)
                        .build()), null);

        StepVerifier.create(store.enqueue(record)).expectNext(true).verifyComplete();
        ArgumentCaptor<String> member = ArgumentCaptor.forClass(String.class);
        verify(zsetOps).add(eq(BillingPendingStore.PENDING_ZSET), member.capture(), anyDouble());
        BillingPendingRecord parsed = JSON.parseObject(member.getValue(), BillingPendingRecord.class);
        assertThat(parsed.attempts()).hasSize(1);
        assertThat(parsed.attempts().get(0).getChannelId()).isEqualTo("c9");
        assertThat(parsed.attempts().get(0).isBilled()).isTrue();
    }
}
