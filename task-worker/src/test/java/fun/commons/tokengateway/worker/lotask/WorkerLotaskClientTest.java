package fun.commons.tokengateway.worker.lotask;

import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskAuthSigner;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkerLotaskClient 测试 (MockWebServer): 信封解析 / 无任务 empty / 401 踢出自愈 /
 * progress 成功本地 bumpVersion / result 终态体 / status 状态提取.
 */
class WorkerLotaskClientTest {

    private MockWebServer server;
    private WorkerLotaskClient client;
    private LotaskAuthSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        TokenGatewayProperties props = new TokenGatewayProperties();
        LotaskFaceConfig lotask = props.getTask().getLotask();
        lotask.setUrl(server.url("/").toString().replaceAll("/$", ""));
        lotask.setReadTimeout(Duration.ofSeconds(2));

        signer = Mockito.mock(LotaskAuthSigner.class);
        doNothing().when(signer).attachSignature(any(), anyString(), anyString(), any());
        when(signer.authorize(any(WebClient.RequestBodySpec.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(signer.authorize(any(WebClient.RequestHeadersSpec.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(signer.invalidate()).thenReturn(Mono.empty());

        client = new WorkerLotaskClient(WebClient.builder(), props, signer);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse okJson(String dataJson) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"ok\",\"data\":" + dataJson + "}");
    }

    @Test
    void poll_parsesClaimedTask() {
        server.enqueue(okJson("""
                {"id":"2095","type":"video","payload":{"model":"m"},
                 "executionToken":99,"version":3,"attempt":1,
                 "leaseExpireAt":"2026-09-02T12:00:00+08:00"}"""));
        ClaimedTask task = client.poll("video", "w-1").block(Duration.ofSeconds(3));
        assertThat(task).isNotNull();
        assertThat(task.id()).isEqualTo("2095");
        assertThat(task.type()).isEqualTo("video");
        assertThat(task.executionToken()).isEqualTo(99L);
        assertThat(task.version()).isEqualTo(3);
        assertThat(task.attempt()).isEqualTo(1);
        assertThat(task.leaseExpireAt()).isNotNull();
        assertThat(task.payload()).isNotNull();
    }

    @Test
    void poll_noTaskOrBadEnvelopeCompletesEmpty() {
        // 无任务: data=null
        server.enqueue(okJson("null"));
        StepVerifier.create(client.poll("video", "w")).verifyComplete();
        // 业务码非 0
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":500,\"message\":\"x\",\"data\":null}"));
        StepVerifier.create(client.poll("video", "w")).verifyComplete();
        // data 缺 id
        server.enqueue(okJson("{\"type\":\"video\"}"));
        StepVerifier.create(client.poll("video", "w")).verifyComplete();
    }

    @Test
    void poll_authKickInvalidatesSharedToken() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        StepVerifier.create(client.poll("video", "w")).verifyComplete();
        verify(signer).invalidate();
    }

    @Test
    void poll_transportErrorSwallowedAsEmpty() {
        server.enqueue(new MockResponse().setResponseCode(503));
        StepVerifier.create(client.poll("video", "w")).verifyComplete();
        verify(signer, never()).invalidate();
    }

    @Test
    void progress_bumpsLocalVersionOnCasSuccess() {
        server.enqueue(okJson("true"));
        ClaimedTask task = new ClaimedTask("2095", "video", null, 7L, 3, 1, null);
        StepVerifier.create(client.progress(task, "render", 50)).verifyComplete();
        // CAS 成功 → 本地 version 同步 +1 (平台不回传)
        assertThat(task.version()).isEqualTo(4);
    }

    @Test
    void progress_failureSwallowedAndNoBump() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        ClaimedTask task = new ClaimedTask("2095", "video", null, 7L, 3, 1, null);
        StepVerifier.create(client.progress(task, "render", 50)).verifyComplete();
        assertThat(task.version()).isEqualTo(3);
    }

    @Test
    void result_successWithPayloadAndErrorVariants() {
        server.enqueue(okJson("true"));
        StepVerifier.create(client.result(
                new ClaimedTask("2095", "video", null, 7L, 3, 1, null),
                "SUCCESS", Map.of("resources", "r"), null, null)).verifyComplete();

        server.enqueue(okJson("true"));
        StepVerifier.create(client.result(
                new ClaimedTask("2095", "video", null, 7L, 3, 1, null),
                "FAILED", null, "UPSTREAM", "upstream down")).verifyComplete();

        // 平台拒绝 (业务码非 0) → error
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":40901,\"message\":\"fencing 失败\",\"data\":null}"));
        StepVerifier.create(client.result(
                new ClaimedTask("2095", "video", null, 7L, 3, 1, null),
                "FAILED", null, "X", "y")).verifyError();
    }

    @Test
    void status_extractsStatusAndHandles401() {
        server.enqueue(okJson("{\"status\":\"RUNNING\"}"));
        assertThat(client.status(new ClaimedTask("2095", "video", null, 7L, 3, 1, null))
                .block(Duration.ofSeconds(3))).isEqualTo("RUNNING");

        server.enqueue(okJson("null"));
        assertThat(client.status(new ClaimedTask("2095", "video", null, 7L, 3, 1, null))
                .block(Duration.ofSeconds(3))).isNull();

        server.enqueue(new MockResponse().setResponseCode(401).setBody("kick"));
        StepVerifier.create(client.status(new ClaimedTask("2095", "video", null, 7L, 3, 1, null)))
                .verifyComplete();
        verify(signer, Mockito.atLeastOnce()).invalidate();
    }
}
