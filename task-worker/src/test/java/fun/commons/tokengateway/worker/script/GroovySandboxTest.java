package fun.commons.tokengateway.worker.script;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GroovySandbox + ScriptExecutor 单测 (《05》§9.5: 黑名单编译期拒绝 + 超时硬上限).
 */
@DisplayName("GroovySandbox / ScriptExecutor")
class GroovySandboxTest {

    private static Map<String, Object> ctx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("payload", Map.of());
        ctx.put("progress", new LinkedHashMap<>());
        return ctx;
    }

    private static ScriptExecutor executor() {
        return new ScriptExecutor(new GroovySandbox(), Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("良民脚本: 三钩子可执行, ctx 读写生效")
    void benignScript() {
        String source = """
                def create(Map ctx) { ctx.progress.step = 1; return [upstreamTaskId: "u1"] }
                def poll(Map ctx) { return [state: "RUNNING"] }
                def resultMapping(Map ctx) { return [resources: [], usage: [:]] }
                """;
        Map<String, Object> ctx = ctx();
        assertThat(executor().invoke("t1", source, "create", ctx, null))
                .containsEntry("upstreamTaskId", "u1");
        assertThat(ctx.get("progress")).isInstanceOf(Map.class);
        assertThat(executor().invoke("t1", source, "poll", ctx, null))
                .containsEntry("state", "RUNNING");
    }

    @Test
    @DisplayName("黑名单: System/Runtime/File/反射 编译期拒绝")
    void blacklistRejectedAtCompile() {
        String[] evil = {
                "def create(Map ctx) { System.exit(0) }",
                "def create(Map ctx) { Runtime.getRuntime().exec(\"ls\") }",
                "def create(Map ctx) { new File(\"/etc/passwd\") }",
                "def create(Map ctx) { Thread.sleep(1) }",
        };
        for (String src : evil) {
            assertThatThrownBy(() -> executor().invoke("evil-" + src.hashCode(), src,
                    "create", ctx(), null))
                    .isInstanceOf(ScriptExecutor.ScriptHookException.class)
                    .hasMessageContaining("安检");
        }
    }

    @Test
    @DisplayName("死循环脚本 → 钩子超时硬上限")
    void infiniteLoopTimesOut() {
        String source = "def create(Map ctx) { while (true) { } }";
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> executor().invoke("loop", source, "create", ctx(), null))
                .isInstanceOf(ScriptExecutor.ScriptHookException.class)
                .hasMessageContaining("超时");
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
    }

    @Test
    @DisplayName("钩子返回非 Map → ScriptHookException")
    void nonMapReturn() {
        String source = "def create(Map ctx) { return 42 }";
        assertThatThrownBy(() -> executor().invoke("badret", source, "create", ctx(), null))
                .isInstanceOf(ScriptExecutor.ScriptHookException.class)
                .hasMessageContaining("Map");
    }
}
