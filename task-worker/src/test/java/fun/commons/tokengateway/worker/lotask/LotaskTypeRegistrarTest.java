package fun.commons.tokengateway.worker.lotask;

import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskAuthSigner;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LotaskTypeRegistrar 单测 (MockWebServer 假 lotask): upsert 契约 / once-per-process
 * 幂等 / 失败移除重试 / 401 自愈 / 未配置 no-op.
 */
@DisplayName("LotaskTypeRegistrar 自动注册")
class LotaskTypeRegistrarTest {

    private MockWebServer server;
    private LotaskTypeRegistrar registrar;
    private LotaskAuthSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();

        TokenGatewayProperties props = new TokenGatewayProperties();
        LotaskFaceConfig lotask = props.getTask().getLotask();
        lotask.setUrl(server.url("/").toString().replaceAll("/$", ""));
        lotask.setReadTimeout(Duration.ofSeconds(2));
        lotask.setPlatformClientId("PLATFORM");
        lotask.setPlatformClientSecret("platform-secret");
        lotask.setPlatformTenantId(2100412430772998146L);
        lotask.setTypeConcurrencyLimit(8);
        lotask.setTypeTimeoutSeconds(3600);
        lotask.setTypeMaxRetries(3);

        signer = Mockito.mock(LotaskAuthSigner.class);
        when(signer.platformBearer()).thenReturn(Mono.just("platform-token"));

        registrar = new LotaskTypeRegistrar(WebClient.builder(), props, signer);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void enqueueOk() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"ok\",\"data\":null}"));
    }

    private void enqueueTypes(String... keys) {
        String arr = java.util.Arrays.stream(keys)
                .map(k -> "{\"typeKey\":\"" + k + "\"}")
                .collect(java.util.stream.Collectors.joining(","));
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"ok\",\"data\":[" + arr + "]}"));
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        return server.takeRequest(3, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("upsert 契约: bearer-only + admin/types + body 七字段全必填")
    void registerSendsUpsertContract() throws Exception {
        enqueueTypes();
        enqueueOk();

        registrar.ensureRegistered("zz-probe-type");
        Thread.sleep(400);

        assertThat(server.getRequestCount()).isEqualTo(2);
        RecordedRequest listed = takeRequest();
        assertThat(listed.getMethod()).isEqualTo("GET");
        assertThat(listed.getHeader("Authorization")).isEqualTo("Bearer platform-token");
        RecordedRequest req = takeRequest();
        assertThat(req.getPath()).isEqualTo("/api/v1/admin/types");
        assertThat(req.getHeader("X-Access-Key")).isNull();
        JSONObject body = JSON.parseObject(req.getBody().readUtf8());
        assertThat(body.getLong("tenantId")).isEqualTo(2100412430772998146L);
        assertThat(body.getString("typeKey")).isEqualTo("zz-probe-type");
        assertThat(body.getString("name")).isEqualTo("zz-probe-type");
        assertThat(body.getIntValue("concurrencyLimit")).isEqualTo(8);
        assertThat(body.getIntValue("timeoutSeconds")).isEqualTo(3600);
        assertThat(body.getIntValue("maxRetries")).isEqualTo(3);
        assertThat(body.getBooleanValue("isEnabled")).isTrue();
    }

    @Test
    @DisplayName("幂等: once-per-process, 同类型二次 ensure 不重发请求")
    void ensureIsOncePerProcess() throws Exception {
        enqueueTypes();
        enqueueOk();
        registrar.ensureRegistered("video");
        takeRequest();
        takeRequest();
        Thread.sleep(100);
        registrar.ensureRegistered("video");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("只补缺: lotask 已有的类型跳过 upsert (不覆盖运营调参)")
    void skipsExistingType() throws Exception {
        enqueueTypes("video");
        registrar.ensureRegistered("video");
        Thread.sleep(400);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(takeRequest().getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("失败移除: 平台拒绝 → ensured 移除, 下 tick 重发 (清单已缓存不再 GET)")
    void failureRemovedForRetry() throws Exception {
        enqueueTypes();
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":40300,\"message\":\"forbidden\",\"data\":null}"));
        registrar.ensureRegistered("flaky");
        Thread.sleep(400);
        assertThat(server.getRequestCount()).isEqualTo(2);

        enqueueOk();
        registrar.ensureRegistered("flaky");
        Thread.sleep(400);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("401 自愈: 平台 token 被拒 → invalidatePlatform 后上抛")
    void http401InvalidatesPlatformIdentity() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("kick"));

        StepVerifier.create(registrar.doRegister("video")).verifyError();
        verify(signer).invalidatePlatform();
    }

    @Test
    @DisplayName("未配置 no-op: platform 凭据缺 → enabled=false, ensure 零请求")
    void disabledWhenPlatformClientMissing() throws Exception {
        TokenGatewayProperties props = new TokenGatewayProperties();
        LotaskFaceConfig lotask = props.getTask().getLotask();
        lotask.setUrl(server.url("/").toString().replaceAll("/$", ""));
        LotaskTypeRegistrar bare = new LotaskTypeRegistrar(WebClient.builder(), props, signer);

        assertThat(bare.enabled()).isFalse();
        bare.ensureRegistered("video");
        Thread.sleep(100);
        assertThat(server.getRequestCount()).isZero();
        verify(signer, never()).platformBearer();
    }
}
