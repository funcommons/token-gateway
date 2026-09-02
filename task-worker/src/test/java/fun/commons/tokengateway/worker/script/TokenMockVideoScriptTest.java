package fun.commons.tokengateway.worker.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token-mock 视频脚本三钩子 fixtures 断言 (联调默认上游; 契约 = token-mock VideoJobHandler).
 */
@DisplayName("token-mock-v1.groovy (video) 三钩子")
class TokenMockVideoScriptTest {

    private static final String SCRIPT =
            GroovyScriptTestHarness.repoScriptsRoot().resolve("video/token-mock-v1.groovy").toString();

    @Test
    @DisplayName("create → id; poll queued→RUNNING; poll completed→SUCCEEDED; resultMapping url/usage")
    void happyPath() throws Exception {
        try (GroovyScriptTestHarness h = new GroovyScriptTestHarness()) {
            Map<String, Object> payload = Map.of(
                    "model", "vid-mock-1", "params", Map.of(), "input", "a cat");
            Map<String, Object> ctx = GroovyScriptTestHarness.ctx(h.upstreamBaseUrl, payload);

            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "token-mock-create-ok.json"));
            Map<String, Object> created = h.invoke(SCRIPT, "create", ctx);
            assertThat(created.get("upstreamTaskId")).isEqualTo("video_mock_a1b2c3d4e5f60718293a4b5c");
            ctx.put("upstreamTaskId", created.get("upstreamTaskId"));

            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "token-mock-poll-running.json"));
            Map<String, Object> running = h.invoke(SCRIPT, "poll", ctx);
            assertThat(running.get("state")).isEqualTo("RUNNING");
            assertThat(running.get("progressHint")).isEqualTo(50);

            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "token-mock-poll-completed.json"));
            Map<String, Object> done = h.invoke(SCRIPT, "poll", ctx);
            assertThat(done.get("state")).isEqualTo("SUCCEEDED");
            ctx.put("raw", done.get("raw"));

            Map<String, Object> mapped = h.invoke(SCRIPT, "resultMapping", ctx);
            @SuppressWarnings("unchecked")
            List<String> resources = (List<String>) mapped.get("resources");
            assertThat(resources).containsExactly(
                    "http://localhost:9999/openai/v1/videos/video_mock_a1b2c3d4e5f60718293a4b5c/content");
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) mapped.get("usage");
            assertThat(((Number) usage.get("seconds")).doubleValue()).isEqualTo(5.0);
            assertThat(usage.get("resolution")).isEqualTo("1080p");
        }
    }

    @Test
    @DisplayName("上游 404 (job 不存在) → RUNNING 不判死 (等下一轮/超时钟兜底)")
    void poll404StaysRunning() throws Exception {
        try (GroovyScriptTestHarness h = new GroovyScriptTestHarness()) {
            Map<String, Object> ctx = GroovyScriptTestHarness.ctx(h.upstreamBaseUrl, Map.of());
            ctx.put("upstreamTaskId", "video_mock_ghost");
            h.enqueueStatus(404, "{\"error\":{\"message\":\"video job not found\",\"type\":\"not_found\"}}");
            Map<String, Object> polled = h.invoke(SCRIPT, "poll", ctx);
            assertThat(polled.get("state")).isEqualTo("RUNNING");
        }
    }
}
