package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.DistributeRequest;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.RecordFailureRequest;
import fun.commons.tokengateway.framework.ApiResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpChannelApi 测试: distribute 透传 / record-success/record-failure / RPC 故障降级.
 */
class HttpChannelApiTest {

    private MockWebServer backend;
    private HttpChannelApi api;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        props.setTimeout(Duration.ofSeconds(2));
        api = new HttpChannelApi(WebClient.builder(), props, new RpcInternalAuth(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    void distributePassesThroughChannelDecision() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"channelId\":\"demo-ch\",\"type\":1,"
                        + "\"baseUrl\":\"http://up\",\"apiKey\":\"sk-ch\",\"protocol\":\"openai\"}}"));
        ApiResponse<DistributeVO> resp = api.distribute(DistributeRequest.builder()
                        .tenantId("t").userId("u").apiKeyId("k").groupId("g")
                        .model("gpt-4o-mini").build())
                .block(Duration.ofSeconds(3));
        assertThat(resp).isNotNull();
        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().getChannelId()).isEqualTo("demo-ch");
        assertThat(resp.getData().getApiKey()).isEqualTo("sk-ch");
        RecordedRequest req = backend.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/api/v1/internal/channels/distribute");
        assertThat(req.getBody().readUtf8()).contains("gpt-4o-mini");
    }

    @Test
    void recordSuccessAndFailureReturnVoidEnvelope() {
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));
        StepVerifier.create(api.recordSuccess("ch-1"))
                .assertNext(r -> assertThat(r.isSuccess()).isTrue())
                .verifyComplete();

        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));
        StepVerifier.create(api.recordFailure("ch-1", new RecordFailureRequest()))
                .assertNext(r -> assertThat(r.isSuccess()).isTrue())
                .verifyComplete();
    }

    @Test
    void rpcFailureDegradesToTimeoutEnvelope() {
        backend.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(api.distribute(DistributeRequest.builder().model("m").build()))
                .assertNext(r -> {
                    assertThat(r.isSuccess()).isFalse();
                    assertThat(r.getMessage()).contains("channel RPC failed");
                })
                .verifyComplete();
    }
}
