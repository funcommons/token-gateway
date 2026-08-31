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
        api = new HttpBillingApi(WebClient.builder(), props, new RpcInternalAuth(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
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
}
