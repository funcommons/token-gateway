package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.config.HealthReportProperties;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChannelHealthReporter 单测 (MockWebServer 假后端).
 *
 * <p>验证 record-success / record-failure 的路径与请求体、开关关闭零请求、RPC 失败吞错.
 */
@DisplayName("ChannelHealthReporter")
class ChannelHealthReporterTest {

    private MockWebServer backend;
    private HealthReportProperties healthProps;
    private ChannelHealthReporter reporter;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        GatewayProperties props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        props.setTimeout(Duration.ofSeconds(2));
        healthProps = new HealthReportProperties();
        reporter = new ChannelHealthReporter(
                new HttpChannelApi(WebClient.builder(),
                        new fun.commons.tokengateway.rpc.CapabilityEndpoints(
                                new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), props),
                        new RpcInternalAuth(props)),
                healthProps);
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("reportSuccess → POST /record-success")
    void reportSuccess() throws Exception {
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0}"));

        StepVerifier.create(reporter.reportSuccess(channel("400"))).verifyComplete();

        RecordedRequest req = backend.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/v1/internal/channels/400/record-success");
        assertThat(req.getMethod()).isEqualTo("POST");
    }

    @Test
    @DisplayName("reportFailure → POST /record-failure 带 tenantId/errorCode/errorMessage")
    void reportFailure() throws Exception {
        backend.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0}"));

        StepVerifier.create(reporter.reportFailure(channel("400"), "100", "502", "upstream error"))
                .verifyComplete();

        RecordedRequest req = backend.takeRequest(1, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/api/v1/internal/channels/400/record-failure");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"tenantId\":\"100\"");
        assertThat(body).contains("\"errorCode\":\"502\"");
        assertThat(body).contains("\"errorMessage\":\"upstream error\"");
    }

    @Test
    @DisplayName("开关关闭 → 零请求")
    void disabledSendsNothing() throws Exception {
        healthProps.setEnabled(false);

        StepVerifier.create(reporter.reportSuccess(channel("400"))).verifyComplete();
        StepVerifier.create(reporter.reportFailure(channel("400"), "100", "502", "x"))
                .verifyComplete();

        assertThat(backend.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("channelId 为空 → 零请求")
    void nullChannelIdSendsNothing() throws Exception {
        StepVerifier.create(reporter.reportSuccess(channel(null))).verifyComplete();
        assertThat(backend.takeRequest(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    @DisplayName("RPC 失败 → 吞错正常完成 (fire-and-forget)")
    void rpcFailureSwallowed() {
        backend.enqueue(new MockResponse().setResponseCode(500)
                .setHeader("Content-Type", "application/json").setBody("{}"));

        StepVerifier.create(reporter.reportSuccess(channel("400"))).verifyComplete();
    }

    private static DistributeVO channel(String channelId) {
        return DistributeVO.builder().channelId(channelId).protocol("openai").build();
    }
}
