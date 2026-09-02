package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskMetaStore.TaskMeta;
import fun.commons.tokengateway.task.state.TaskNoMappingStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * 资源代理控制器测试: 验签 fail-closed / 缓存命中直发 / 404-409-404 负路径 /
 * 回源带渠道 apiKey + write-through 落盘.
 */
class ResourceProxyControllerTest {

    @TempDir
    Path cacheDir;

    private MockWebServer upstream;
    private ResourceProxyController controller;
    private ResourceSigner signer;
    private TaskNoMappingStore mappingStore;
    private TaskMetaStore metaStore;
    private LotaskTaskClient lotaskClient;
    private TokenGatewayProperties props;

    private static final String TASK_NO = "T20950324ABCD";

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();

        props = new TokenGatewayProperties();
        props.getTask().setResourceCacheDir(cacheDir.toString());

        signer = Mockito.mock(ResourceSigner.class);
        mappingStore = Mockito.mock(TaskNoMappingStore.class);
        metaStore = Mockito.mock(TaskMetaStore.class);
        lotaskClient = Mockito.mock(LotaskTaskClient.class);

        controller = new ResourceProxyController(signer, mappingStore, metaStore,
                lotaskClient, WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        upstream.shutdown();
    }

    private void allow() {
        when(signer.verify(anyString(), anyInt(), anyLong(), anyString())).thenReturn(true);
    }

    private static TaskMeta meta(String apiKey) {
        return new TaskMeta("lotask-1", "pc-1", "video", null, 0L, apiKey);
    }

    private static LotaskTaskView view(String status, Map<String, Object> result) {
        return new LotaskTaskView("lotask-1", status, result, null, null);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> fetch() {
        return controller.fetch(TASK_NO, 0, 123L, "sig");
    }

    private void mappedTask(String status, Map<String, Object> result, String apiKey) {
        when(mappingStore.get(TASK_NO)).thenReturn(Mono.just("lotask-1"));
        when(metaStore.getMeta(TASK_NO)).thenReturn(Mono.just(meta(apiKey)));
        when(lotaskClient.get("lotask-1")).thenReturn(Mono.just(view(status, result)));
    }

    @Test
    void badSignatureFailsClosed() {
        when(signer.verify(anyString(), anyInt(), anyLong(), anyString())).thenReturn(false);
        StepVerifier.create(fetch())
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(RelayException.class);
                    assertThat(((RelayException) e).getCode())
                            .isEqualTo(ApiCode.PARAM_ERROR.getCode());
                })
                .verify();
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void cacheHitServesFileWithoutAnyRemoteCall() {
        allow();
        Path file = cacheDir.resolve(TASK_NO).resolve("0");
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, "cached-bytes".getBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        StepVerifier.create(fetch())
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(200);
                    String body = DataBufferUtils.join(resp.getBody())
                            .map(db -> {
                                byte[] b = new byte[db.readableByteCount()];
                                db.read(b);
                                DataBufferUtils.release(db);
                                return new String(b);
                            })
                            .block();
                    assertThat(body).isEqualTo("cached-bytes");
                })
                .verifyComplete();
        assertThat(upstream.getRequestCount()).isZero();
    }

    @Test
    void unknownTaskMaps404() {
        allow();
        when(mappingStore.get(TASK_NO)).thenReturn(Mono.empty());
        StepVerifier.create(fetch())
                .expectErrorSatisfies(e -> assertThat(((RelayException) e).getCode())
                        .isEqualTo(ApiCode.NOT_FOUND.getCode()))
                .verify();
    }

    @Test
    void nonSucceededTaskConflicts409() {
        allow();
        mappedTask("RUNNING", null, null);
        StepVerifier.create(fetch())
                .expectErrorSatisfies(e -> assertThat(((RelayException) e).getCode())
                        .isEqualTo(ApiCode.STATE_CONFLICT.getCode()))
                .verify();
    }

    @Test
    void indexOutOfBounds404() {
        allow();
        mappedTask("SUCCESS", Map.of("resources", List.of()), null);
        StepVerifier.create(fetch())
                .expectErrorSatisfies(e -> assertThat(((RelayException) e).getCode())
                        .isEqualTo(ApiCode.NOT_FOUND.getCode()))
                .verify();
    }

    @Test
    void fetchCachesUpstreamWithChannelKey() throws Exception {
        allow();
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("fresh-upstream-bytes"));
        mappedTask("SUCCESS", Map.of("resources", List.of(upstream.url("/file.mp4").toString())),
                "sk-channel-key");

        StepVerifier.create(fetch())
                .assertNext(resp -> {
                    assertThat(resp.getStatusCode().value()).isEqualTo(200);
                    String body = DataBufferUtils.join(resp.getBody())
                            .map(db -> {
                                byte[] b = new byte[db.readableByteCount()];
                                db.read(b);
                                DataBufferUtils.release(db);
                                return new String(b);
                            })
                            .block();
                    assertThat(body).isEqualTo("fresh-upstream-bytes");
                })
                .verifyComplete();

        RecordedRequest req = upstream.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-channel-key");
        // write-through: 第二次走缓存盘, 不再回源
        assertThat(Files.readString(cacheDir.resolve(TASK_NO).resolve("0")))
                .isEqualTo("fresh-upstream-bytes");
        StepVerifier.create(fetch()).expectNextCount(1).verifyComplete();
        assertThat(upstream.getRequestCount()).isEqualTo(1);
    }

    @Test
    void noChannelKeySendsNoAuthHeader() throws Exception {
        allow();
        upstream.enqueue(new MockResponse().setBody("anon"));
        mappedTask("SUCCESS", Map.of("resources", List.of(upstream.url("/f").toString())), null);
        StepVerifier.create(fetch()).expectNextCount(1).verifyComplete();
        RecordedRequest req = upstream.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req.getHeader("Authorization")).isNull();
    }
}
