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
 * 回源带渠道 apiKey + write-through 落盘 / 终态条目优先回源 (issue #43 存储格式翻转:
 * 新格式原始 URL 直读条目值, 旧格式代理路径回退 lotask 链路).
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
        // issue #43 存储格式翻转: 回源解析终态条目优先; 缺省无条目 → 回退 lotask 链路 (现状)
        when(metaStore.getTerminalResult(anyString())).thenReturn(Mono.empty());

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
        return controller.fetch(TASK_NO, "0", 123L, "sig");
    }

    /** body 拼接保持在响应式链内 (assertNext 内 block() 在 epoll 线程发射时抛 IllegalStateException). */
    private static Mono<String> joinBody(ResponseEntity<Flux<DataBuffer>> resp) {
        return DataBufferUtils.join(resp.getBody()).map(db -> {
            byte[] b = new byte[db.readableByteCount()];
            db.read(b);
            DataBufferUtils.release(db);
            return new String(b);
        });
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
        StepVerifier.create(fetch().flatMap(resp -> {
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            return joinBody(resp);
        }))
                .assertNext(body -> assertThat(body).isEqualTo("cached-bytes"))
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

        // body 断言并入响应式链 (assertNext 内 block() 在 epoll 线程发射时炸 — flaky, 修复于 issue #18 CI)
        StepVerifier.create(fetch().flatMap(resp -> {
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            return joinBody(resp);
        }))
                .assertNext(body -> assertThat(body).isEqualTo("fresh-upstream-bytes"))
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

    @Test
    void dataUriDecodedInlineWithoutUpstream() throws Exception {
        // 上游同步 API 经 url 字段回 data: URI (OpenAI 兼容面实测形态) — 不回源, 直解码落盘
        allow();
        byte[] png = new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        String dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        mappedTask("SUCCESS", Map.of("resources", List.of(dataUri), "usage", Map.of()), "sk-x");

        StepVerifier.create(fetch().flatMap(resp -> {
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            return DataBufferUtils.join(resp.getBody()).map(db -> {
                byte[] b = new byte[db.readableByteCount()];
                db.read(b);
                DataBufferUtils.release(db);
                return new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            });
        }))
                .assertNext(body -> assertThat(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo(png))
                .verifyComplete();
        // data URI 无上游调用
        assertThat(upstream.getRequestCount()).isZero();
        // 落盘缓存生效: 内容一致
        assertThat(Files.readAllBytes(cacheDir.resolve(TASK_NO).resolve("0"))).isEqualTo(png);
    }

    @Test
    void dataUriMalformedRejects() {
        allow();
        mappedTask("SUCCESS", Map.of("resources", List.of("data:image/png;base64,!!!非法!!!"), "usage", Map.of()), null);
        StepVerifier.create(fetch())
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(RelayException.class);
                })
                .verify();
    }

    @Test
    void emptyCacheFileDoesNotPoison() throws Exception {
        // write-through 半途失败会留 0 字节缓存 — 不得视为命中 (否则永发空文件), 须回源重拉
        allow();
        Path file = cacheDir.resolve(TASK_NO).resolve("0");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[0]);
        byte[] png = new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47 };
        mappedTask("SUCCESS",
                Map.of("resources", List.of(upstream.url("/") + "img.png"), "usage", Map.of()), "sk-x");
        upstream.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody(new okio.Buffer().write(png)));

        StepVerifier.create(fetch())
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();
        assertThat(Files.readAllBytes(file)).isEqualTo(png);
    }

    // ---------- issue #43 存储格式翻转: 终态条目优先回源 ----------

    @Test
    void terminalEntryRawUrlFetchesEntryValueDirectly() throws Exception {
        // 新格式条目 (存改写后原始 URL): 直读条目值回源, 不触 lotask/mapping
        // —— e2e 断言回源目标是改写后消费方 OSS URL 而非渠道原值
        allow();
        String consumerOssUrl = upstream.url("/oss/rewritten.mp4").toString();
        when(metaStore.getTerminalResult(TASK_NO)).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"SUCCEEDED\",\"result\":{"
                        + "\"resources\":[\"" + consumerOssUrl + "\"]}}")));
        when(metaStore.getMeta(TASK_NO)).thenReturn(Mono.just(meta("sk-channel-key")));
        // lotask 侧即便有值也是渠道原值 — 不得被回源
        when(mappingStore.get(TASK_NO)).thenReturn(Mono.just("lotask-1"));
        when(lotaskClient.get("lotask-1")).thenReturn(Mono.just(
                view("SUCCESS", Map.of("resources", List.of(upstream.url("/channel/raw.mp4").toString())))));
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "video/mp4").setBody("rewritten-oss-bytes"));

        StepVerifier.create(fetch().flatMap(resp -> {
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            return joinBody(resp);
        }))
                .assertNext(body -> assertThat(body).isEqualTo("rewritten-oss-bytes"))
                .verifyComplete();

        RecordedRequest req = upstream.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/oss/rewritten.mp4");
        // 渠道 apiKey 附着两路不变 (公共读 OSS 忽略未知头无害)
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk-channel-key");
        Mockito.verify(lotaskClient, Mockito.never()).get(anyString());
        Mockito.verify(mappingStore, Mockito.never()).get(anyString());
    }

    @Test
    void legacyProxyEntryFallsBackToLotask() throws Exception {
        // 旧格式条目 (存代理路径, 不可回源): 回退 lotask 视图原始值链路 (逐字节现状)
        allow();
        when(metaStore.getTerminalResult(TASK_NO)).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"SUCCEEDED\",\"result\":{"
                        + "\"resources\":[\"/v1/resources/" + TASK_NO + "/0?exp=1&sig=stale\"]}}")));
        String rawUrl = upstream.url("/channel/raw.mp4").toString();
        mappedTask("SUCCESS", Map.of("resources", List.of(rawUrl)), null);
        upstream.enqueue(new MockResponse().setBody("lotask-raw-bytes"));

        StepVerifier.create(fetch().flatMap(resp -> {
            assertThat(resp.getStatusCode().value()).isEqualTo(200);
            return joinBody(resp);
        }))
                .assertNext(body -> assertThat(body).isEqualTo("lotask-raw-bytes"))
                .verifyComplete();

        RecordedRequest req = upstream.takeRequest(3, TimeUnit.SECONDS);
        assertThat(req).isNotNull();
        assertThat(req.getPath()).isEqualTo("/channel/raw.mp4");
        // 回退 lotask 链路已走 (存量 defaultIfEmpty 装配期双调形态, 不限定次数, 只证链路命中)
        Mockito.verify(lotaskClient, Mockito.atLeastOnce()).get("lotask-1");
    }

    @Test
    void terminalEntryDataUriDecodesInline() throws Exception {
        // 新格式条目 + data: URI: 直解码落盘, 不回源 (与 lotask 链路同口径)
        allow();
        byte[] png = new byte[]{ (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        String dataUri = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png);
        when(metaStore.getTerminalResult(TASK_NO)).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"SUCCEEDED\",\"result\":{"
                        + "\"resources\":[\"" + dataUri + "\"]}}")));
        when(metaStore.getMeta(TASK_NO)).thenReturn(Mono.empty());

        StepVerifier.create(fetch())
                .assertNext(resp -> assertThat(resp.getStatusCode().value()).isEqualTo(200))
                .verifyComplete();
        assertThat(upstream.getRequestCount()).isZero();
        assertThat(Files.readAllBytes(cacheDir.resolve(TASK_NO).resolve("0"))).isEqualTo(png);
        Mockito.verify(lotaskClient, Mockito.never()).get(anyString());
    }

    @Test
    void failedEntryFallsBackToLotask409() {
        // 非 SUCCEEDED 条目无原始值可取 → 回退 lotask 链路 (409 语义不变)
        allow();
        when(metaStore.getTerminalResult(TASK_NO)).thenReturn(Mono.just(
                com.alibaba.fastjson2.JSON.parseObject("{\"status\":\"FAILED\","
                        + "\"error\":{\"code\":\"X\"}}")));
        mappedTask("FAILED", null, null);
        StepVerifier.create(fetch())
                .expectErrorSatisfies(e -> assertThat(((RelayException) e).getCode())
                        .isEqualTo(ApiCode.STATE_CONFLICT.getCode()))
                .verify();
    }
}
