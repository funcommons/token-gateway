package fun.commons.tokengateway.worker.script;

import fun.commons.tokengateway.worker.config.WorkerProperties;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * 脚本测试 harness (《05》§9.4 单测层): 真实沙箱 + 真实执行器 + MockWebServer 假上游,
 * fixtures 目录的上游响应样本按序回放, 断言三钩子输出. 不触真实网络.
 *
 * <p>用法见 {@code SampleVideoScriptTest}; 新上游脚本照抄该测试换 fixtures 即可.
 */
public class GroovyScriptTestHarness implements AutoCloseable {

    private final MockWebServer upstream;
    private final ScriptExecutor executor;
    private final ScriptHttpClient http;
    public final String upstreamBaseUrl;

    public GroovyScriptTestHarness() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
        upstreamBaseUrl = upstream.url("/").toString().replaceAll("/$", "");
        WorkerProperties props = new WorkerProperties();
        props.setHookTimeout(Duration.ofSeconds(10));
        props.setEgressAllowlist(java.util.List.of(upstreamBaseUrl));
        executor = new ScriptExecutor(new GroovySandbox(), props.getHookTimeout());
        http = new ScriptHttpClient(WebClient.builder(), props);
    }

    /** 回放一个上游响应 (fixtures/*.json 内容). */
    public GroovyScriptTestHarness enqueueFixture(String fixtureJson) {
        upstream.enqueue(new okhttp3.mockwebserver.MockResponse()
                .setHeader("Content-Type", "application/json").setBody(fixtureJson));
        return this;
    }

    /** 回放一个非 2xx 上游响应 (错误路径). */
    public GroovyScriptTestHarness enqueueStatus(int status, String body) {
        upstream.enqueue(new okhttp3.mockwebserver.MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json").setBody(body));
        return this;
    }

    /** 执行钩子 (cacheKey 用脚本路径; ctx 由调用方构造). */
    public Map<String, Object> invoke(String scriptPath, String hook, Map<String, Object> ctx) {
        try {
            String source = Files.readString(Path.of(scriptPath));
            return executor.invoke(scriptPath, source, hook, ctx, http);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 标准 ctx: routeSnapshot 指向假上游. */
    public static Map<String, Object> ctx(String upstreamBaseUrl, Map<String, Object> payload) {
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("payload", payload);
        ctx.put("routeSnapshot", Map.of("baseUrl", upstreamBaseUrl, "apiKey", "sk-test"));
        ctx.put("progress", new java.util.LinkedHashMap<>());
        return ctx;
    }

    /** 仓根 scripts/ 解析 (surefire 工作目录是模块目录, 需回退一级). */
    public static Path repoScriptsRoot() {
        Path direct = Path.of("scripts");
        return Files.isDirectory(direct) ? direct : Path.of("..", "scripts");
    }

    public static String fixture(String taskType, String name) {
        try {
            return Files.readString(repoScriptsRoot().resolve(taskType).resolve("fixtures").resolve(name));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws Exception {
        upstream.shutdown();
    }
}
