package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.PreConsumeRequest;
import fun.commons.tokengateway.contract.PreConsumeVO;
import fun.commons.tokengateway.contract.RefundRequest;
import fun.commons.tokengateway.contract.SettleRequest;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpBillingApi 测试.
 */
@DisplayName("HttpBillingApi")
class HttpBillingApiTest {

    private MockWebServer backend;
    private HttpBillingApi api;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        props.setTimeout(Duration.ofSeconds(2));
        api = new HttpBillingApi(WebClient.builder(), new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("issue #14: billing path-prefix 可配 → 三端点前缀切换 (默认 chat 路径不变)")
    void billingPathPrefixConfigurable() throws Exception {
        var spi = new TokenGatewayProperties();
        spi.getBilling().setPathPrefix("/v1/internal/billing/task");
        var legacy = new GatewayProperties();
        legacy.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        api = new HttpBillingApi(WebClient.builder(),
                new CapabilityEndpoints(spi, legacy), new RpcInternalAuth(legacy));

        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pc-2\",\"success\":true}}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.preConsume(PreConsumeRequest.builder().userId("u1").build()))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue()).verifyComplete();
        StepVerifier.create(api.settle(SettleRequest.builder().preConsumeId("pc-2").build()))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(api.refund(RefundRequest.builder().preConsumeId("pc-2").build()))
                .expectNextCount(1).verifyComplete();

        assertThat(backend.takeRequest().getPath()).isEqualTo("/v1/internal/billing/task/pre-consume");
        assertThat(backend.takeRequest().getPath()).isEqualTo("/v1/internal/billing/task/settle");
        assertThat(backend.takeRequest().getPath()).isEqualTo("/v1/internal/billing/task/refund");
    }

    @Test
    @DisplayName("preConsume 成功: 200 + preConsumeId/estimatedQuota 透传")
    void preConsumeSuccess() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pc-1\","
                        + "\"estimatedQuota\":1.50,\"success\":true}}"));

        StepVerifier.create(api.preConsume(PreConsumeRequest.builder()
                        .userId("u1").tokenId("t1").channelId("c1").model("gpt-4o").build()))
                .assertNext(resp -> {
                    assertThat(resp.isSuccess()).isTrue();
                    assertThat(resp.getData().getPreConsumeId()).isEqualTo("pc-1");
                    assertThat(resp.getData().getEstimatedQuota()).isEqualByComparingTo(new BigDecimal("1.50"));
                })
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/billing/pre-consume");
    }

    @Test
    @DisplayName("settle 成功: 200 + Void")
    void settleSuccess() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.settle(SettleRequest.builder()
                        .preConsumeId("pc-1").actualPromptTokens(10).actualCompletionTokens(5)
                        .success(true).build()))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue())
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/billing/settle");
    }

    @Test
    @DisplayName("refund 成功: 200 + Void")
    void refundSuccess() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.refund(RefundRequest.builder()
                        .preConsumeId("pc-1").reason("upstream 502").build()))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue())
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/billing/refund");
    }

    @Test
    @DisplayName("preConsume 上游 500 → onErrorResume 降级")
    void preConsumeUpstreamError() {
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(api.preConsume(PreConsumeRequest.builder().build()))
                .assertNext(resp -> {
                    assertThat(resp.isFail()).isTrue();
                    assertThat(resp.getCode()).isEqualTo(ApiCode.SERVICE_TIMEOUT.getCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("settle 超时 → onErrorResume 降级")
    void settleTimeout() {
        backend.enqueue(new MockResponse()
                .setBody("{\"code\":0,\"data\":null}")
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        StepVerifier.create(api.settle(SettleRequest.builder().build()))
                .assertNext(resp -> {
                    assertThat(resp.isFail()).isTrue();
                    assertThat(resp.getCode()).isEqualTo(ApiCode.SERVICE_TIMEOUT.getCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("issue #31: 任务族三端点走 task.billing.path-prefix 寻址")
    void taskFamilyHitsTaskPrefix() throws Exception {
        var spi = new TokenGatewayProperties();
        spi.getTask().getBilling().setPathPrefix("/v1/internal/billing/task");
        var legacy = new GatewayProperties();
        legacy.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        legacy.setTimeout(Duration.ofSeconds(2));
        api = new HttpBillingApi(WebClient.builder(),
                new CapabilityEndpoints(spi, legacy), new RpcInternalAuth(legacy));

        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"preConsumeId\":\"pc-t\",\"success\":true}}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.preConsumeTask(PreConsumeRequest.builder().userId("u1").amount(7).build()))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue()).verifyComplete();
        StepVerifier.create(api.settleTask(SettleRequest.builder().preConsumeId("pc-t").build()))
                .expectNextCount(1).verifyComplete();
        StepVerifier.create(api.refundTask(RefundRequest.builder().preConsumeId("pc-t").build()))
                .expectNextCount(1).verifyComplete();

        assertThat(backend.takeRequest().getPath()).isEqualTo("/v1/internal/billing/task/pre-consume");
        assertThat(backend.takeRequest().getPath()).isEqualTo("/v1/internal/billing/task/settle");
        assertThat(backend.takeRequest().getPath()).isEqualTo("/v1/internal/billing/task/refund");
    }

    @Test
    @DisplayName("issue #31 面归属互斥: 配了 task 前缀, 通用族 settle 仍走通用前缀 (不串 task 面)")
    void genericFamilyUnaffectedByTaskPrefix() throws Exception {
        var spi = new TokenGatewayProperties();
        spi.getTask().getBilling().setPathPrefix("/v1/internal/billing/task");
        var legacy = new GatewayProperties();
        legacy.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        legacy.setTimeout(Duration.ofSeconds(2));
        api = new HttpBillingApi(WebClient.builder(),
                new CapabilityEndpoints(spi, legacy), new RpcInternalAuth(legacy));

        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"creditConsumed\":0.5}}"));

        StepVerifier.create(api.settle(SettleRequest.builder().preConsumeId("pc-1").build()))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue()).verifyComplete();

        assertThat(backend.takeRequest().getPath()).isEqualTo("/api/v1/internal/billing/settle");
    }

    @Test
    @DisplayName("issue #31 回归红线: task.billing 未配, 任务族路径 == 现全局前缀 (存量行为不变)")
    void taskFamilyFallsBackToGlobalPrefixWhenUnconfigured() throws Exception {
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(api.refundTask(RefundRequest.builder().preConsumeId("pc-1").build()))
                .assertNext(resp -> assertThat(resp.isSuccess()).isTrue()).verifyComplete();

        assertThat(backend.takeRequest().getPath()).isEqualTo("/api/v1/internal/billing/refund");
    }
}
