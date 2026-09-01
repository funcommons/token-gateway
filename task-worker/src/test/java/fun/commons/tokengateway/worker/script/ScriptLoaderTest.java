package fun.commons.tokengateway.worker.script;

import fun.commons.tokengateway.worker.config.WorkerProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScriptLoader 单测: 目录索引 + 版本序取最新 + reload 热更.
 */
@DisplayName("ScriptLoader")
class ScriptLoaderTest {

    @TempDir
    Path scriptsDir;

    private ScriptLoader loader() {
        WorkerProperties props = new WorkerProperties();
        props.setScriptsDir(scriptsDir.toString());
        return new ScriptLoader(props);
    }

    @Test
    @DisplayName("按目录组织索引; 同类型取文件名序最大 (v2 > v1)")
    void latestVersionWins() throws Exception {
        Files.createDirectories(scriptsDir.resolve("video"));
        Files.writeString(scriptsDir.resolve("video/up-v1.groovy"), "// v1");
        Files.writeString(scriptsDir.resolve("video/up-v2.groovy"), "// v2");
        Files.createDirectories(scriptsDir.resolve("image"));
        Files.writeString(scriptsDir.resolve("image/sd-v1.groovy"), "// img");

        ScriptLoader loader = loader();
        loader.reload();
        assertThat(loader.taskTypes()).containsExactlyInAnyOrder("video", "image");
        assertThat(loader.forType("video").orElseThrow().path()).endsWith("up-v2.groovy");
        assertThat(loader.forType("audio")).isEmpty();
    }

    @Test
    @DisplayName("reload 拾取新脚本 (热更)")
    void reloadPicksUp() throws Exception {
        Files.createDirectories(scriptsDir.resolve("video"));
        Files.writeString(scriptsDir.resolve("video/up-v1.groovy"), "// v1");
        ScriptLoader loader = loader();
        loader.reload();
        assertThat(loader.taskTypes()).containsExactly("video");

        Files.writeString(scriptsDir.resolve("video/up-v9.groovy"), "// v9 hot");
        loader.reload();
        assertThat(loader.forType("video").orElseThrow().path()).endsWith("up-v9.groovy");
    }
}
