package fun.commons.tokengateway.task.billing;

import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.idempotency.IdempotencyStore;
import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import fun.commons.tokengateway.rpc.HttpBillingApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskBillingSaga 面归属测试 (issue #31): 三端点实际请求路径 == task 前缀
 * ({@code token-gateway.task.billing.path-prefix}); 未显式配置时 == 现全局前缀
 * (回归红线, 存量 #12 部署零迁移). MockWebServer 真 RPC 断言.
 */
@DisplayName("TaskBillingSaga 面归属 (issue #31)")
class TaskBillingSagaTest {

    private MockWebServer backend;
    private TaskBillingSaga saga;

    /** 退款幂等直通 (占位恒成功, 聚焦路径断言). */
    private static IdempotencyStore permissiveStore() {
        return new IdempotencyStore() {
            @Override
            public Mono<Boolean> tryAcquire(String key, Duration ttl) {
                return Mono.just(true);
            }

            @Override
            public Mono<Void> release(String key) {
                return Mono.empty();
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    private void wireSaga(java.util.function.Consumer<TokenGatewayProperties> spiTuner) {
        var spi = new TokenGatewayProperties();
        spiTuner.accept(spi);
        var legacy = new GatewayProperties();
        legacy.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        legacy.setTimeout(Duration.ofSeconds(2));
        saga = new TaskBillingSaga(new HttpBillingApi(WebClient.builder(),
                new CapabilityEndpoints(spi, legacy), new RpcInternalAuth(legacy)), permissiveStore());
    }

    private static TokenValidateVO token() {
        TokenValidateVO vo = new TokenValidateVO();
        vo.setTenantId("t1");
        vo.setUserId("u1");
        vo.setTokenId("tk1");
        return vo;
    }

    @Test
    @DisplayName("task.billing.path-prefix 配置: pre-consume/settle/refund 三端点实走 task 前缀")
    void taskPrefixRoutesAllThreeEndpoints() throws Exception {
        wireSaga(spi -> spi.getTask().getBilling().setPathPrefix("/v1/internal/billing/task"));

        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pc-1\",\"success\":true}}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"creditConsumed\":1.00}}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(saga.preConsumeFull(token(), "ch1", "PLATFORM", "vid-mock-1",
                        "req-1", 777))
                .assertNext(preConsumeId -> assertThat(preConsumeId).isEqualTo("pc-1"))
                .verifyComplete();
        StepVerifier.create(saga.settleOnce("pc-1", "req-1")).verifyComplete();
        StepVerifier.create(saga.refundOnce("pc-1", "upstream 502", "req-1")).verifyComplete();

        RecordedRequest preConsume = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(preConsume).isNotNull();
        assertThat(preConsume.getPath()).isEqualTo("/v1/internal/billing/task/pre-consume");
        String preConsumeBody = preConsume.getBody().readUtf8();
        // 任务契约: 全额 amount 直传 + estimatedTokens 恒 0 + requestId 即接入方幂等键
        assertThat(preConsumeBody).contains("\"amount\":777");
        assertThat(preConsumeBody).contains("\"estimatedPromptTokens\":0");
        assertThat(preConsumeBody).contains("\"estimatedCompletionTokens\":0");
        assertThat(preConsumeBody).contains("\"requestId\":\"req-1\"");

        assertThat(backend.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/v1/internal/billing/task/settle");
        RecordedRequest refund = backend.takeRequest(2, TimeUnit.SECONDS);
        assertThat(refund.getPath()).isEqualTo("/v1/internal/billing/task/refund");
        assertThat(refund.getBody().readUtf8()).contains("\"preConsumeId\":\"pc-1\"");
    }

    @Test
    @DisplayName("回归红线: task.billing 未配, 三端点路径 == 现全局前缀 (存量行为不变)")
    void unconfiguredTaskBillingKeepsGlobalPrefix() throws Exception {
        wireSaga(spi -> {
        });

        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pc-2\",\"success\":true}}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(saga.preConsumeFull(token(), "ch1", "PLATFORM", "vid-mock-1", "req-2"))
                .assertNext(preConsumeId -> assertThat(preConsumeId).isEqualTo("pc-2"))
                .verifyComplete();
        StepVerifier.create(saga.settleOnce("pc-2", "req-2")).verifyComplete();
        StepVerifier.create(saga.refundOnce("pc-2", "expired", "req-2")).verifyComplete();

        assertThat(backend.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/v1/internal/billing/pre-consume");
        assertThat(backend.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/v1/internal/billing/settle");
        assertThat(backend.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/v1/internal/billing/refund");
    }
}
