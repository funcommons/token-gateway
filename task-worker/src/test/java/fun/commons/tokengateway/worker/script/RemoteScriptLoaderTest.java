package fun.commons.tokengateway.worker.script;

import fun.commons.tokengateway.worker.config.WorkerProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ScriptLoader remote 源单测 (issue #9): MockWebServer 假控制层, 验证
 * 拉取契约 / 版本比对跳过 / 降级副本 / 首启磁盘自举 / fail-fast.
 */
@DisplayName("ScriptLoader remote 源")
class RemoteScriptLoaderTest {

    @TempDir
    Path scriptsDir;

    private MockWebServer controlPlane;
    private WorkerProperties props;

    @BeforeEach
    void setUp() throws Exception {
        controlPlane = new MockWebServer();
        controlPlane.start();
        props = new WorkerProperties();
        props.setScriptsDir(scriptsDir.toString());
        props.setScriptSource(WorkerProperties.ScriptSource.REMOTE);
        props.getScriptRemote().setBaseUrl(
                controlPlane.url("/").toString().replaceAll("/$", "")
                        + "/api/v1/internal/work-scripts");
        props.getScriptRemote().setTaskTypes(java.util.List.of("video"));
        props.getScriptRemote().setCacheTtl(java.time.Duration.ofMillis(50));
    }

    @AfterEach
    void tearDown() throws Exception {
        controlPlane.shutdown();
    }

    private ScriptLoader loader() {
        return new ScriptLoader(props, WebClient.builder());
    }

    private void enqueueScript(String version, String body) {
        controlPlane.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"version\":\"" + version
                        + "\",\"hooks\":" + quote(body) + "}}"));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    @Test
    @DisplayName("拉取成功 → 索引生效 + 落本地副本 + 鉴权头透传")
    void fetchWritesCache() throws Exception {
        enqueueScript("v3", "def create(ctx) { [:] }");
        ScriptLoader loader = loader();
        loader.reload();

        assertThat(loader.taskTypes()).containsExactly("video");
        assertThat(loader.forType("video").orElseThrow().source())
                .contains("def create");
        Path cache = scriptsDir.resolve(".cache/video/v-v3.groovy");
        assertThat(cache).exists();
        assertThat(Files.readString(cache)).contains("def create");

        var recorded = controlPlane.takeRequest();
        assertThat(recorded.getPath())
                .isEqualTo("/api/v1/internal/work-scripts/video");
    }

    @Test
    @DisplayName("鉴权: internal-token 配置即发 X-Internal-Token")
    void internalTokenHeaderAttached() throws Exception {
        props.getScriptRemote().setInternalToken("jwt-xyz");
        enqueueScript("v1", "// s");
        loader().reload();
        assertThat(controlPlane.takeRequest().getHeader("X-Internal-Token"))
                .isEqualTo("jwt-xyz");
    }

    @Test
    @DisplayName("cacheTtl 窗口内跳过 HTTP; 过期后重拉且版本不变不落新盘")
    void ttlThrottleAndVersionSkip() throws Exception {
        enqueueScript("v1", "// v1");
        ScriptLoader loader = loader();
        loader.reload();
        int afterFirst = controlPlane.getRequestCount();

        loader.reload(); // ttl 窗口内 → 不发 HTTP
        assertThat(controlPlane.getRequestCount()).isEqualTo(afterFirst);

        Thread.sleep(80); // 过 ttl
        enqueueScript("v1", "// v1");
        loader.reload();
        assertThat(controlPlane.getRequestCount()).isEqualTo(afterFirst + 1);
        // 版本未变 → 索引仍指向同一副本
        assertThat(loader.forType("video").orElseThrow().path())
                .endsWith("v-v1.groovy");
    }

    @Test
    @DisplayName("版本变更 → 新副本生效, 旧副本清理")
    void versionBumpSwapsAsset() throws Exception {
        enqueueScript("v1", "// v1");
        ScriptLoader loader = loader();
        loader.reload();

        Thread.sleep(80);
        enqueueScript("v2", "// v2 hot");
        loader.reload();

        assertThat(loader.forType("video").orElseThrow().source()).contains("v2 hot");
        assertThat(scriptsDir.resolve(".cache/video/v-v2.groovy")).exists();
        assertThat(scriptsDir.resolve(".cache/video/v-v1.groovy")).doesNotExist();
    }

    @Test
    @DisplayName("remote 不可达 → 降级本地最后副本 (索引不丢, 下轮重试)")
    void degradeToCacheOnOutage() throws Exception {
        enqueueScript("v1", "// v1");
        ScriptLoader loader = loader();
        loader.reload();

        Thread.sleep(80);
        controlPlane.enqueue(new MockResponse().setResponseCode(500).setBody("down"));
        loader.reload();
        assertThat(loader.forType("video").orElseThrow().source()).contains("// v1");
        assertThat(loader.taskTypes()).containsExactly("video");

        // 恢复后新版本生效 (失败不占 ttl 窗口)
        enqueueScript("v2", "// v2 recovered");
        loader.reload();
        assertThat(loader.forType("video").orElseThrow().source()).contains("recovered");
    }

    @Test
    @DisplayName("首启控制层不可达 → 从磁盘缓存自举 (Worker 不空转)")
    void bootFromDiskCache() throws Exception {
        Path dir = scriptsDir.resolve(".cache/video");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("v-v7.groovy"), "// cached v7");

        controlPlane.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        ScriptLoader loader = loader();
        loader.reload();
        assertThat(loader.forType("video").orElseThrow().source()).contains("cached v7");
    }

    @Test
    @DisplayName("fail-fast: remote 缺 base-url → IllegalStateException")
    void missingBaseUrlFailsFast() {
        props.getScriptRemote().setBaseUrl(" ");
        assertThrows(IllegalStateException.class, () -> loader().reload());
    }

    @Test
    @DisplayName("fail-fast: remote task-types 为空 (无缓存) → IllegalStateException")
    void emptyTaskTypesFailsFast() {
        props.getScriptRemote().setTaskTypes(java.util.List.of());
        assertThrows(IllegalStateException.class, () -> loader().reload());
    }

    @Test
    @DisplayName("信封无效 (code!=0) → 按拉取失败处理, 保留既有副本")
    void invalidEnvelopeDegrades() throws Exception {
        enqueueScript("v1", "// v1");
        ScriptLoader loader = loader();
        loader.reload();

        Thread.sleep(80);
        controlPlane.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":10400,\"message\":\"脚本未配置\"}"));
        loader.reload();
        assertThat(loader.forType("video").orElseThrow().source()).contains("// v1");
    }

    @Test
    @DisplayName("local 模式不受 .cache 影响 (dot 目录不索引)")
    void localModeIgnoresCacheDir() throws Exception {
        Path cache = scriptsDir.resolve(".cache/video");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("v-v1.groovy"), "// cache only");
        Path real = scriptsDir.resolve("video");
        Files.createDirectories(real);
        Files.writeString(real.resolve("up-v1.groovy"), "// real");

        props.setScriptSource(WorkerProperties.ScriptSource.LOCAL);
        ScriptLoader loader = loader();
        loader.reload();
        assertThat(loader.taskTypes()).containsExactly("video");
        assertThat(loader.forType("video").orElseThrow().source()).contains("// real");
    }
}
