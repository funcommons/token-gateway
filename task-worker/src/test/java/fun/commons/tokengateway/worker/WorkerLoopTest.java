package fun.commons.tokengateway.worker;

import fun.commons.tokengateway.task.lotask.RouteSnapshotCipher;
import fun.commons.tokengateway.worker.config.WorkerProperties;
import fun.commons.tokengateway.worker.lotask.ClaimedTask;
import fun.commons.tokengateway.worker.lotask.WorkerLotaskClient;
import fun.commons.tokengateway.worker.script.GroovySandbox;
import fun.commons.tokengateway.worker.script.GroovyScriptTestHarness;
import fun.commons.tokengateway.worker.script.ScriptHttpClient;
import fun.commons.tokengateway.worker.script.ScriptLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorkerLoop 端到端 (假上游 fixtures + mock lotask): claim → create → poll → resultMapping
 * → reportResult(SUCCESS, {resources, usage}).
 */
@DisplayName("WorkerLoop")
class WorkerLoopTest {

    private static final String CIPHER_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("任务跑通: 快照解密 → 三钩子 → SUCCESS 上报 (fencing token 回传)")
    void runTaskSuccess() throws Exception {
        try (GroovyScriptTestHarness h = new GroovyScriptTestHarness()) {
            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "create-ok.json"));
            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "poll-succeeded.json"));

            WorkerProperties props = new WorkerProperties();
            props.setUpstreamPollInterval(Duration.ofMillis(10));
            props.setStatusCheckEvery(100);   // 本用例不触发取消检测
            props.setEgressAllowlist(List.of(h.upstreamBaseUrl));

            RouteSnapshotCipher cipher = new RouteSnapshotCipher(CIPHER_KEY);
            String snapshot = cipher.encrypt(com.alibaba.fastjson2.JSON.toJSONString(
                    Map.of("baseUrl", h.upstreamBaseUrl, "apiKey", "sk-test")));

            ScriptLoader loader = new ScriptLoader(props);
            // 直接注入脚本资产 (绕开目录扫描)
            var assetField = ScriptLoader.class.getDeclaredField("byType");
            assetField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ScriptLoader.ScriptAsset> byType =
                    (Map<String, ScriptLoader.ScriptAsset>) assetField.get(loader);
            String scriptPath = GroovyScriptTestHarness.repoScriptsRoot()
                    .resolve("video/sample-v1.groovy").toString();
            byType.put("video", new ScriptLoader.ScriptAsset("video", scriptPath, 1L,
                    java.nio.file.Files.readString(java.nio.file.Path.of(scriptPath))));

            WorkerLotaskClient lotask = mock(WorkerLotaskClient.class);
            when(lotask.progress(any(), anyString(), anyInt())).thenReturn(Mono.empty());
            when(lotask.result(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            WorkerLoop loop = new WorkerLoop(lotask, loader, new GroovySandbox(),
                    new ScriptHttpClient(WebClient.builder(), props), cipher, props);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", "sample-v1");
            payload.put("params", Map.of("resolution", "720p"));
            payload.put("input", "a cat");
            payload.put("routeSnapshot", snapshot);
            loop.runTask(new ClaimedTask("lotask-1", "video", payload, 42L, 7, 1, null));

            ArgumentCaptor<Map<String, Object>> result = ArgumentCaptor.forClass(Map.class);
            verify(lotask).result(any(), eq("SUCCESS"), result.capture(), isNull(), isNull());
            @SuppressWarnings("unchecked")
            List<String> resources = (List<String>) result.getValue().get("resources");
            assertThat(resources).containsExactly("https://upstream-cdn/videos/up-123.mp4");

            // fencing: progress/result 回传 executionToken+version
            ArgumentCaptor<ClaimedTask> taskCap = ArgumentCaptor.forClass(ClaimedTask.class);
            verify(lotask, atLeastOnce()).progress(taskCap.capture(), anyString(), anyInt());
            assertThat(taskCap.getValue().executionToken()).isEqualTo(42L);
            assertThat(taskCap.getValue().version()).isEqualTo(7);
        }
    }

    @Test
    @DisplayName("脚本异常 → FAILED + SCRIPT_ERROR 上报")
    void scriptErrorReportsFailed() throws Exception {
        try (GroovyScriptTestHarness h = new GroovyScriptTestHarness()) {
            // create 钩子收到 500 → 脚本抛 RuntimeException → FAILED + SCRIPT_ERROR
            h.enqueueStatus(500, "{\"code\":10500,\"message\":\"upstream busy\"}");

            WorkerProperties props = new WorkerProperties();
            props.setEgressAllowlist(List.of(h.upstreamBaseUrl));
            RouteSnapshotCipher cipher = new RouteSnapshotCipher(CIPHER_KEY);
            String snapshot = cipher.encrypt(com.alibaba.fastjson2.JSON.toJSONString(
                    Map.of("baseUrl", h.upstreamBaseUrl, "apiKey", "sk-test")));

            ScriptLoader loader = new ScriptLoader(props);
            var assetField = ScriptLoader.class.getDeclaredField("byType");
            assetField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, ScriptLoader.ScriptAsset> byType =
                    (Map<String, ScriptLoader.ScriptAsset>) assetField.get(loader);
            String scriptPath = GroovyScriptTestHarness.repoScriptsRoot()
                    .resolve("video/sample-v1.groovy").toString();
            byType.put("video", new ScriptLoader.ScriptAsset("video", scriptPath, 1L,
                    java.nio.file.Files.readString(java.nio.file.Path.of(scriptPath))));

            WorkerLotaskClient lotask = mock(WorkerLotaskClient.class);
            when(lotask.progress(any(), anyString(), anyInt())).thenReturn(Mono.empty());
            when(lotask.result(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            WorkerLoop loop = new WorkerLoop(lotask, loader, new GroovySandbox(),
                    new ScriptHttpClient(WebClient.builder(), props), cipher, props);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", "sample-v1");
            payload.put("routeSnapshot", snapshot);
            // create 钩子对 500 直接抛异常 → FAILED + SCRIPT_ERROR (快速路径)
            loop.runTask(new ClaimedTask("lotask-2", "video", payload, 1L, 1, 1, null));

            verify(lotask).result(any(), eq("FAILED"), isNull(),
                    anyString(), anyString());
        }
    }

    // -------------------------------------------------------- tick 批量拉取

    /** 零 HTTP 三钩子脚本 (同步 SUCCEEDED, tick 批量用例不依赖上游 fixtures). */
    private static final String TRIVIAL_SCRIPT = """
            def create(ctx) { [upstreamTaskId: 'up-' + ctx.payload.taskNo] }
            def poll(ctx) { [state: 'SUCCEEDED'] }
            def resultMapping(ctx) { [resources: ['mock://' + ctx.payload.taskNo]] }
            """;

    @TempDir
    Path tmp;

    /** 真临时脚本目录 + 零 HTTP 脚本 (tick() 起手 reload 重扫磁盘, 反射注入会被清空). */
    private WorkerLoop loopWithTrivialScript(WorkerProperties props,
            WorkerLotaskClient lotask) throws Exception {
        props.setScriptsDir(tmp.toString());
        props.setUpstreamPollInterval(Duration.ofMillis(10));
        props.setStatusCheckEvery(100);   // 用例不触发取消检测
        Path dir = tmp.resolve("batchtest");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("inline-v1.groovy"), TRIVIAL_SCRIPT);

        ScriptLoader loader = new ScriptLoader(props);
        loader.reload();
        WorkerLoop loop = new WorkerLoop(lotask, loader, new GroovySandbox(),
                new ScriptHttpClient(WebClient.builder(), props),
                new RouteSnapshotCipher(CIPHER_KEY), props);
        return loop;
    }

    private static ClaimedTask task(String id, int no) {
        return new ClaimedTask(id, "batchtest", Map.of("taskNo", no), 1L, 1, 1, null);
    }

    @Test
    @DisplayName("tick 批量拉取: 队列 3 单 batch=4 → 单 tick 全派发 (3 拉 + 1 空轮收口)")
    void tickDrainsQueueInBatch() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setPullBatchSize(4);
        WorkerLotaskClient lotask = mock(WorkerLotaskClient.class);
        when(lotask.progress(any(), anyString(), anyInt())).thenReturn(Mono.empty());
        when(lotask.result(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        when(lotask.poll(anyString(), anyString())).thenReturn(
                Mono.just(task("t-1", 1)), Mono.just(task("t-2", 2)),
                Mono.just(task("t-3", 3)), Mono.empty());
        WorkerLoop loop = loopWithTrivialScript(props, lotask);

        loop.tick();

        // 单 tick 拉满队列: 3 单 + 1 空轮 (空轮即收口, 不会第 5 次)
        verify(lotask, timeout(5000).times(4)).poll(anyString(), anyString());
        verify(lotask, timeout(10000).times(3)).result(any(), eq("SUCCESS"),
                any(), isNull(), isNull());
    }

    @Test
    @DisplayName("tick 批量上限: batch=2 且队列持续有单 → 单 tick 恰拉 2 次收口")
    void tickRespectsBatchCap() throws Exception {
        WorkerProperties props = new WorkerProperties();
        props.setPullBatchSize(2);
        WorkerLotaskClient lotask = mock(WorkerLotaskClient.class);
        when(lotask.progress(any(), anyString(), anyInt())).thenReturn(Mono.empty());
        when(lotask.result(any(), anyString(), any(), any(), any())).thenReturn(Mono.empty());
        when(lotask.poll(anyString(), anyString())).thenReturn(
                Mono.just(task("t-1", 1)), Mono.just(task("t-2", 2)));
        WorkerLoop loop = loopWithTrivialScript(props, lotask);

        loop.tick();

        verify(lotask, timeout(10000).times(2)).result(any(), eq("SUCCESS"),
                any(), isNull(), isNull());
        verify(lotask, timeout(5000).times(2)).poll(anyString(), anyString());
    }
}
