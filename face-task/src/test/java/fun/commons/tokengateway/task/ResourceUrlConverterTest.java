package fun.commons.tokengateway.task;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourceUrlConverter 读时转换 (issue #43 存储格式翻转对偶): 终态条目存原始值,
 * convertAtRead 逐元素判定 —— 原始 URL 现签代理 URL; 旧格式代理路径剥查询串重签自愈
 * (24h 旧签名过期不再显形); 密钥缺失 fail-closed 口径与写时 convert 一致.
 */
@DisplayName("ResourceUrlConverter#convertAtRead (读时签名)")
class ResourceUrlConverterTest {

    private TokenGatewayProperties props;
    private ResourceUrlConverter converter;

    @BeforeEach
    void setUp() {
        props = new TokenGatewayProperties();
        props.getTask().setResourceSignKey("test-sign-key");
        converter = new ResourceUrlConverter(new ResourceSigner(props));
    }

    @Test
    @DisplayName("新格式原始 URL → 现签代理 URL (含 usage.outputType 扩展名), 原始值不透出")
    void rawUrlSignedAtRead() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", List.of("https://consumer-oss/rewritten.mp4", "data:image/png;base64,AAA"));
        result.put("usage", Map.of("outputType", "mp4"));

        Map<String, Object> out = converter.convertAtRead("T9", result);

        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) out.get("resources");
        assertThat(resources).hasSize(2);
        assertThat(resources.get(0)).startsWith("/v1/resources/T9/0.mp4?exp=");
        assertThat(resources.get(1)).startsWith("/v1/resources/T9/1.mp4?exp=");
        assertThat(out.get("resources").toString())
                .doesNotContain("consumer-oss").doesNotContain("data:image");
        // 原 map 不被就地改 (返回副本)
        assertThat(result.get("resources").toString()).contains("consumer-oss");
    }

    @Test
    @DisplayName("旧格式代理路径 → 剥查询串重签 (sig 换新, exp 新鲜, 路径与 .ext 后缀保留)")
    void legacyProxyResigned() {
        String stale = "/v1/resources/T9/0.mp4?exp=1&sig=stale-sig";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", List.of(stale));

        Map<String, Object> out = converter.convertAtRead("T9", result);

        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) out.get("resources");
        assertThat(resources).hasSize(1);
        String resigned = resources.get(0);
        assertThat(resigned).startsWith("/v1/resources/T9/0.mp4?exp=");
        assertThat(resigned).doesNotContain("stale-sig").doesNotContain("exp=1&");
        long exp = Long.parseLong(resigned.replaceAll(".*[?&]exp=(\\d+).*", "$1"));
        assertThat(exp).isGreaterThan(System.currentTimeMillis() / 1000);
    }

    @Test
    @DisplayName("混合列表: 代理路径重签 + 原始 URL 现签 (按下标各归各位)")
    void mixedList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", List.of(
                "/v1/resources/T9/0?exp=1&sig=old", "https://upstream/raw.mp4"));

        Map<String, Object> out = converter.convertAtRead("T9", result);

        @SuppressWarnings("unchecked")
        List<String> resources = (List<String>) out.get("resources");
        assertThat(resources.get(0)).startsWith("/v1/resources/T9/0?exp=")
                .doesNotContain("sig=old");
        assertThat(resources.get(1)).startsWith("/v1/resources/T9/1?exp=")
                .doesNotContain("upstream");
    }

    @Test
    @DisplayName("密钥缺失: 原始 URL fail-closed 清空 (同写时 convert), 旧代理路径原样保留")
    void missingKeyFailClosedForRawKeepsProxy() {
        props.getTask().setResourceSignKey(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", List.of("https://upstream/raw.mp4"));
        Map<String, Object> out = converter.convertAtRead("T9", result);
        assertThat((List<?>) out.get("resources")).isEmpty();

        // 旧代理路径无密钥也验签不了, 原样返回不额外损失
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("resources", List.of("/v1/resources/T9/0?exp=1&sig=x"));
        Map<String, Object> out2 = converter.convertAtRead("T9", legacy);
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) out2.get("resources");
        assertThat(kept).containsExactly("/v1/resources/T9/0?exp=1&sig=x");
    }

    @Test
    @DisplayName("畸形代理路径原样返回 (不造签名); 无 resources 键原样返回")
    void malformedProxyPathKeptAsIs() {
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("resources", List.of("/v1/resources/onlytask"));
        Map<String, Object> out = converter.convertAtRead("T9", weird);
        @SuppressWarnings("unchecked")
        List<String> kept = (List<String>) out.get("resources");
        assertThat(kept).containsExactly("/v1/resources/onlytask");

        Map<String, Object> noResources = new LinkedHashMap<>();
        noResources.put("usage", Map.of("seconds", 1));
        assertThat(converter.convertAtRead("T9", noResources)).doesNotContainKey("resources");
    }

    @Test
    @DisplayName("写时 convert 语义不变: 原始 URL → 代理 URL (回归锚)")
    void writeTimeConvertUnchanged() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resources", List.of("https://upstream/raw.mp4"));
        Map<String, Object> out = converter.convert("T9", result);
        assertThat(out.get("resources").toString())
                .contains("/v1/resources/T9/0?exp=").doesNotContain("upstream");
    }
}
