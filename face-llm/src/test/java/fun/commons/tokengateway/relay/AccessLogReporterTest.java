package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.contract.AccessLogRequest;
import fun.commons.tokengateway.contract.OwnerType;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.contract.TokenValidateVO;
import fun.commons.tokengateway.rpc.HttpAccessLogApi;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AccessLogReporter")
class AccessLogReporterTest {

    private MockWebServer backend;
    private AccessLogReporter reporter;
    private RelayOrchestrator.PreparedRequest prepared;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        props.setTimeout(Duration.ofSeconds(2));
        reporter = new AccessLogReporter(new fun.commons.tokengateway.rpc.HttpAccessLogApi(
                WebClient.builder(), props,
                new fun.commons.tokengateway.rpc.RpcInternalAuth(props)),
                TestChannelHealthReporters.disabled());

        TokenValidateVO token = TokenValidateVO.builder()
                .tenantId("100").userId("200").tokenId("300").build();
        DistributeVO channel = DistributeVO.builder()
                .channelId("400").protocol("openai").ownerType(OwnerType.TENANT).build();
        prepared = new RelayOrchestrator.PreparedRequest(token, channel, null, "req-1", null, java.util.List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("reportSuccess: 字段透传 trace_id/tenantId/modelCode/latencyMs/billingMode")
    void reportSuccess() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporter.reportSuccess(prepared, "gpt-4o",
                        "/v1/chat/completions", 36, 16, 5, java.math.BigDecimal.valueOf(7.5), 250, "trace-xyz"))
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/access-log/record");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"modelCode\":\"gpt-4o\"");
        assertThat(body).contains("\"requestPath\":\"/v1/chat/completions\"");
        assertThat(body).contains("\"statusCode\":200");
        assertThat(body).contains("\"tenantId\":100");
        assertThat(body).contains("\"userId\":200");
        assertThat(body).contains("\"apiKeyId\":300");
        assertThat(body).contains("\"channelId\":400");
        assertThat(body).contains("\"promptTokens\":36");
        assertThat(body).contains("\"completionTokens\":16");
        assertThat(body).contains("\"cachedTokens\":5");
        assertThat(body).contains("\"latencyMs\":250");
        assertThat(body).contains("\"billingMode\":\"TENANT_SOLO\"");
        assertThat(body).contains("\"creditConsumed\":7.5");
        assertThat(body).contains("\"traceId\":\"trace-xyz\"");
        assertThat(body).contains("\"requestMethod\":\"POST\"");
    }

    @Test
    @DisplayName("reportError: statusCode 透传, tokens=0")
    void reportError() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporter.reportError(prepared, "claude-3",
                        "/v1/messages", 502, 1500, null))
                .verifyComplete();

        String body = backend.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"statusCode\":502");
        assertThat(body).contains("\"promptTokens\":0");
        assertThat(body).contains("\"completionTokens\":0");
    }

    @Test
    @DisplayName("prepared=null → 直接 Mono.empty() (不调 RPC)")
    void preparedNullSkipped() {
        StepVerifier.create(reporter.reportSuccess(null, "m", "/p", 0, 0, 0, null, 0, null))
                .verifyComplete();
        assertThat(backend.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("RPC 失败 → onErrorResume 不抛 (fire-and-forget)")
    void rpcFailureSwallowed() {
        backend.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(reporter.reportSuccess(prepared, "m", "/p", 0, 0, 0, null, 0, null))
                .verifyComplete();
    }

    // ---- issue #1: 渠道健康分流 (recording 桩) ----

    private AccessLogReporter reporting(java.util.List<String> calls) {
        return new AccessLogReporter(new fun.commons.tokengateway.rpc.HttpAccessLogApi(
                WebClient.builder(), props(),
                new fun.commons.tokengateway.rpc.RpcInternalAuth(props())),
                TestChannelHealthReporters.recording(calls));
    }

    private fun.commons.tokengateway.config.GatewayProperties props() {
        var p = new fun.commons.tokengateway.config.GatewayProperties();
        p.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        p.setTimeout(Duration.ofSeconds(2));
        return p;
    }

    @Test
    @DisplayName("issue #1 缺口3: 上游 401 → record-failure 携带真实 errorCode HTTP_401")
    void upstream4xxRecordsFailureWithRealCode() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporting(calls).reportError(prepared, "m", "/p", 401, 100, "t1"))
                .verifyComplete();

        String body = backend.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"statusCode\":401");
        assertThat(calls).containsExactly("failure:400:HTTP_401");
    }

    @Test
    @DisplayName("issue #1 缺口3: 上游 429 → HTTP_429 (限流可区分)")
    void upstream429RecordsHttp429() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporting(calls).reportError(prepared, "m", "/p", 429, 100, "t2"))
                .verifyComplete();

        backend.takeRequest();
        assertThat(calls).containsExactly("failure:400:HTTP_429");
    }

    @Test
    @DisplayName("客户端取消 499 → 访问日志照记, 渠道健康不上报")
    void clientCancel499SkipsHealth() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporting(calls).reportError(prepared, "m", "/p", 499, 100, "t3"))
                .verifyComplete();

        String body = backend.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"statusCode\":499");
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("issue #1 缺口2: reportErrorWithoutHealth → 访问日志照记, 不触 record-failure")
    void withoutHealthSkipsChannelFailure() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporting(calls).reportErrorWithoutHealth(prepared, "m", "/p", 500, 100, "t4"))
                .verifyComplete();

        String body = backend.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"statusCode\":500");
        assertThat(calls).isEmpty();
    }

    @Test
    @DisplayName("成功路径: record-success 照常触发 (recording 桩)")
    void successRecordsChannelSuccess() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":null}"));

        StepVerifier.create(reporting(calls).reportSuccess(prepared, "m", "/p", 1, 1, 0,
                        java.math.BigDecimal.ONE, 50, "t5"))
                .verifyComplete();

        backend.takeRequest();
        assertThat(calls).containsExactly("success:400");
    }
}
