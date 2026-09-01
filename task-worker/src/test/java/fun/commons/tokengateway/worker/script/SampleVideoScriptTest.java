package fun.commons.tokengateway.worker.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 样例视频脚本三钩子 fixtures 断言 (《05》§9.4; 新上游脚本照抄本类).
 */
@DisplayName("sample-v1.groovy (video) 三钩子")
class SampleVideoScriptTest {

    private static final String SCRIPT =
            GroovyScriptTestHarness.repoScriptsRoot().resolve("video/sample-v1.groovy").toString();

    @Test
    @DisplayName("create → upstreamTaskId; poll(SUCCEEDED) → resultMapping 资源契约")
    void happyPath() throws Exception {
        try (GroovyScriptTestHarness h = new GroovyScriptTestHarness()) {
            Map<String, Object> payload = Map.of(
                    "model", "sample-v1", "params", Map.of("resolution", "720p"),
                    "input", "a cat");
            Map<String, Object> ctx = GroovyScriptTestHarness.ctx(h.upstreamBaseUrl, payload);

            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "create-ok.json"));
            Map<String, Object> created = h.invoke(SCRIPT, "create", ctx);
            assertThat(created.get("upstreamTaskId")).isEqualTo("up-123");
            ctx.put("upstreamTaskId", created.get("upstreamTaskId"));

            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "poll-succeeded.json"));
            Map<String, Object> polled = h.invoke(SCRIPT, "poll", ctx);
            assertThat(polled.get("state")).isEqualTo("SUCCEEDED");
            ctx.put("raw", polled.get("raw"));

            Map<String, Object> mapped = h.invoke(SCRIPT, "resultMapping", ctx);
            @SuppressWarnings("unchecked")
            List<String> resources = (List<String>) mapped.get("resources");
            assertThat(resources).containsExactly("https://upstream-cdn/videos/up-123.mp4");
            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) mapped.get("usage");
            assertThat(usage.get("seconds")).isEqualTo(5);
            assertThat(usage.get("resolution")).isEqualTo("720p");
        }
    }

    @Test
    @DisplayName("poll(FAILED) → state=FAILED + error 透传")
    void failedPath() throws Exception {
        try (GroovyScriptTestHarness h = new GroovyScriptTestHarness()) {
            Map<String, Object> ctx = GroovyScriptTestHarness.ctx(h.upstreamBaseUrl, Map.of());
            ctx.put("upstreamTaskId", "up-123");
            h.enqueueFixture(GroovyScriptTestHarness.fixture("video", "poll-failed.json"));
            Map<String, Object> polled = h.invoke(SCRIPT, "poll", ctx);
            assertThat(polled.get("state")).isEqualTo("FAILED");
            assertThat(String.valueOf(polled.get("error"))).contains("moderation");
        }
    }
}
