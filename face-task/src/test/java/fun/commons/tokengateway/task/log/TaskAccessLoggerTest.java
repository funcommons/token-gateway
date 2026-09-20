package fun.commons.tokengateway.task.log;

import fun.commons.tokengateway.rpc.CapabilityEndpoints;
import fun.commons.tokengateway.rpc.HttpAccessLogApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TaskAccessLogger 单测 (issue #29): MockWebServer 断言 record 请求体字段
 * (taskNo 附 requestPath 查询参数 / modelCode / tenantId 等身份 / traceId)
 * + 失败不阻塞 (record/validate 5xx → 链正常完成, 纪律同 LLM 面 AccessLogReporter).
 *
 * <p>能力面平移态回退: 各面 url 未配置时统一回退 legacy gateway.backend.url —
 * 单 MockWebServer 顺序应答 validate + record 两个端点.
 */
@DisplayName("TaskAccessLogger")
class TaskAccessLoggerTest {

    private MockWebServer backend;
    private TaskAccessLogger logger;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var legacy = new fun.commons.tokengateway.config.GatewayProperties();
        legacy.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        legacy.setTimeout(Duration.ofSeconds(2));
        var endpoints = new CapabilityEndpoints(new TokenGatewayProperties(), legacy);
        var internalAuth = new RpcInternalAuth(legacy);
        logger = new TaskAccessLogger(
                new HttpTokenApi(WebClient.builder(), endpoints, internalAuth),
                new HttpAccessLogApi(WebClient.builder(), endpoints, internalAuth));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("受理上报: validate 回查身份 → record 落 taskNo/model/tenantId/traceId/latency")
    void createdChainCarriesFields() throws Exception {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tenantId\":\"100\","
                + "\"userId\":\"200\",\"tokenId\":\"300\"}}"));
        backend.enqueue(json("{\"code\":0,\"data\":null}"));

        StepVerifier.create(logger.createdChain("/v1/onetoken/videos", "wan-2.2", "T1",
                        "sk-x", "1.2.3.4", "trace-xyz", 250))
                .verifyComplete();

        var validate = backend.takeRequest();
        assertThat(validate.getPath()).isEqualTo("/api/v1/internal/tokens/validate");
        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/access-log/record");
        String body = recorded.getBody().readUtf8();
        // taskNo 无独立契约字段 (#25/#26 定稿不改契约) — 附 requestPath 查询参数
        assertThat(body).contains("\"requestPath\":\"/v1/onetoken/videos?task_no=T1\"");
        assertThat(body).contains("\"modelCode\":\"wan-2.2\"");
        assertThat(body).contains("\"tenantId\":100");
        assertThat(body).contains("\"userId\":200");
        assertThat(body).contains("\"apiKeyId\":300");
        assertThat(body).contains("\"traceId\":\"trace-xyz\"");
        assertThat(body).contains("\"statusCode\":200");
        assertThat(body).contains("\"latencyMs\":250");
        assertThat(body).contains("\"requestMethod\":\"POST\"");
    }

    @Test
    @DisplayName("受理上报: record 5xx → 链正常完成 (失败不阻塞, HttpAccessLogApi 内部吞错)")
    void createdChainSurvivesRecord5xx() throws Exception {
        backend.enqueue(json("{\"code\":0,\"data\":{\"valid\":true,\"tenantId\":\"100\","
                + "\"userId\":\"200\",\"tokenId\":\"300\"}}"));
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(logger.createdChain("/v1/videos", "sora-2", "T2",
                        "sk-x", null, "tr-1", 30))
                .verifyComplete();
        assertThat(backend.takeRequest().getPath()).isEqualTo("/api/v1/internal/tokens/validate");
        assertThat(backend.takeRequest().getPath()).isEqualTo("/api/v1/internal/access-log/record");
    }

    @Test
    @DisplayName("受理上报: validate 失败 (fail 包络) → 记录照落, 身份留空")
    void createdChainKeepsRecordWhenValidateFails() throws Exception {
        backend.enqueue(new MockResponse().setResponseCode(500));
        backend.enqueue(json("{\"code\":0,\"data\":null}"));

        StepVerifier.create(logger.createdChain("/v1/onetoken/audios", "tts-1", "T3",
                        "sk-x", null, "tr-2", 15))
                .verifyComplete();

        backend.takeRequest();
        String body = backend.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"requestPath\":\"/v1/onetoken/audios?task_no=T3\"");
        assertThat(body).contains("\"modelCode\":\"tts-1\"");
        assertThat(body).contains("\"tenantId\":null");
        assertThat(body).contains("\"apiKeyId\":null");
    }

    @Test
    @DisplayName("终态上报: record 落 webhook 路径 + task_no/status 查询参数 + modelCode=submitTaskType")
    void terminalChainCarriesFields() throws Exception {
        backend.enqueue(json("{\"code\":0,\"data\":null}"));

        StepVerifier.create(logger.terminalChain("T9", "video", "SUCCEEDED"))
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/access-log/record");
        String body = recorded.getBody().readUtf8();
        assertThat(body).contains("\"requestPath\":\"/internal/lotask/webhook?task_no=T9&status=SUCCEEDED\"");
        assertThat(body).contains("\"modelCode\":\"video\"");
        assertThat(body).contains("\"statusCode\":200");
        assertThat(body).contains("\"requestMethod\":\"POST\"");
        assertThat(body).contains("\"traceId\":\"");
    }

    @Test
    @DisplayName("终态上报: record 5xx → 链正常完成 (不阻塞终态处理链)")
    void terminalChainSurvivesRecord5xx() throws Exception {
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(logger.terminalChain("T9", "video", "FAILED"))
                .verifyComplete();
    }

    @Test
    @DisplayName("taskNoOf: OneToken task_no / OpenAI id / PROCESSING 降级 / 同步生图 data[0].url 四形状")
    void taskNoOfShapes() {
        assertThat(TaskAccessLogger.taskNoOf(Map.of("task_no", "T1"))).isEqualTo("T1");
        assertThat(TaskAccessLogger.taskNoOf(Map.of("id", "T2", "status", "queued"))).isEqualTo("T2");
        assertThat(TaskAccessLogger.taskNoOf(Map.of("status", "PROCESSING", "task_no", "T4")))
                .isEqualTo("T4");
        assertThat(TaskAccessLogger.taskNoOf(Map.of("created", 1, "data",
                List.of(Map.of("url", "/v1/resources/T3/0?exp=1&sig=x"))))).isEqualTo("T3");
        assertThat(TaskAccessLogger.taskNoOf(null)).isNull();
        assertThat(TaskAccessLogger.taskNoOf(Map.of("created", 1))).isNull();
    }

    @Test
    @DisplayName("modelOf: 顶层 model 提取, null 安全")
    void modelOfBody() {
        assertThat(TaskAccessLogger.modelOf(Map.of("model", "wan-2.2"))).isEqualTo("wan-2.2");
        assertThat(TaskAccessLogger.modelOf(Map.of("prompt", "p"))).isNull();
        assertThat(TaskAccessLogger.modelOf(null)).isNull();
    }
}
