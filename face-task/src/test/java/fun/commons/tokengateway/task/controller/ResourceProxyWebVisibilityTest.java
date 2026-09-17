package fun.commons.tokengateway.task.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源代理 Web 可看性单测 (2026-09-17 实测反馈: 代理响应 octet-stream + 无扩展名,
 * 浏览器不内联渲染) — 路径段扩展名剥离 / Content-Type 三层解析 / 文件名后缀.
 */
@DisplayName("ResourceProxy Web 可看性")
class ResourceProxyWebVisibilityTest {

    @Test
    @DisplayName("parseIndex: {index}[.{ext}] 双形态; 非法段 → -1")
    void parseIndexShapes() {
        assertThat(ResourceProxyController.parseIndex("0")).isZero();
        assertThat(ResourceProxyController.parseIndex("0.png")).isZero();
        assertThat(ResourceProxyController.parseIndex("1.jpg")).isEqualTo(1);
        assertThat(ResourceProxyController.parseIndex("3.webp")).isEqualTo(3);
        assertThat(ResourceProxyController.parseIndex("abc")).isEqualTo(-1);
        assertThat(ResourceProxyController.parseIndex("-1")).isEqualTo(-1);
        assertThat(ResourceProxyController.parseIndex(null)).isEqualTo(-1);
    }

    @Test
    @DisplayName("resolveUpstreamMediaType: 上游 image/png 直取; octet-stream 回退 URL 扩展名")
    void resolveUpstreamMediaTypeLayers() {
        // ① 上游明确 image/png → 直取
        assertThat(ResourceProxyController.resolveUpstreamMediaType(
                "https://up/x/abc", MediaType.IMAGE_PNG)).isEqualTo(MediaType.IMAGE_PNG);
        // ② 上游 octet-stream (COS/常见对象存储口径) → 按上游 URL .png 扩展名判
        assertThat(ResourceProxyController.resolveUpstreamMediaType(
                "https://up/x/abc.png?v=1", MediaType.APPLICATION_OCTET_STREAM))
                .isEqualTo(MediaType.IMAGE_PNG);
        // ③ 都没有 → octet-stream 兜底
        assertThat(ResourceProxyController.resolveUpstreamMediaType(
                "https://up/x/noext", null)).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
    }

    @Test
    @DisplayName("sniffMediaType: PNG/JPEG 魔数识别 (旧缓存无 sidecar 兜底)")
    void sniffMagicBytes(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path png = dir.resolve("a");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});
        assertThat(ResourceProxyController.sniffMediaType(png)).isEqualTo(MediaType.IMAGE_PNG);

        Path jpg = dir.resolve("b");
        Files.write(jpg, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0});
        assertThat(ResourceProxyController.sniffMediaType(jpg)).isEqualTo(MediaType.IMAGE_JPEG);

        Path unknown = dir.resolve("c");
        Files.write(unknown, new byte[]{1, 2, 3, 4});
        assertThat(ResourceProxyController.sniffMediaType(unknown)).isNull();
    }

    @Test
    @DisplayName("extensionOf: jpeg→jpg / svg+xml→svg / 未知→bin")
    void extensionMapping() {
        assertThat(ResourceProxyController.extensionOf(MediaType.IMAGE_PNG)).isEqualTo("png");
        assertThat(ResourceProxyController.extensionOf(MediaType.IMAGE_JPEG)).isEqualTo("jpg");
        assertThat(ResourceProxyController.extensionOf(MediaType.parseMediaType("image/svg+xml")))
                .isEqualTo("svg");
        assertThat(ResourceProxyController.extensionOf(
                MediaType.parseMediaType("application/x-weird"))).isEqualTo("bin");
        assertThat(ResourceProxyController.extensionOf(null)).isEqualTo("bin");
    }
}
