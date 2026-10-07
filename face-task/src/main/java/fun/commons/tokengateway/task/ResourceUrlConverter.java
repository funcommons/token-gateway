package fun.commons.tokengateway.task;

import fun.commons.tokengateway.task.resource.ResourceSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上游 resources → 网关代理 URL 转换 (webhook notify 发送时 / poll 读时两路共用).
 *
 * <p>《05》§4 永不透传: 上游 URL 只允许以 /v1/resources/{taskNo}/{idx}?exp=&amp;sig=
 * 代理形态出现; 签名密钥缺失时 fail-closed 清空 resources (对账兜底重放重建).
 *
 * <p><b>存储格式翻转 (issue #43 根治)</b>: 终态条目存<b>原始 (改写后) URL</b> 不再存
 * 转换后代理 URL —— 签名一律读时现签 (新鲜 24h 窗, 存量旧格式条目里 24h 前写死的
 * exp+sig 经 {@link #convertAtRead} 剥查询串重签自愈); 资源代理回源按条目原始值直取
 * (result-filter 改写后的消费方 OSS URL 因此真正生效).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceUrlConverter {

    /** 代理路径前缀 (旧格式终态条目识别). */
    static final String PROXY_PREFIX = "/v1/resources/";

    private final ResourceSigner resourceSigner;

    /**
     * 写时/发送时转换 (原始 → 代理): notify body 与 poll 非终态透传两路用.
     * 返回 result 副本, resources 已就地转换为代理 URL.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> convert(String taskNo, Map<String, Object> result) {
        Map<String, Object> converted = result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
        Object resources = converted.get("resources");
        if (!(resources instanceof List<?> list)) {
            return converted;
        }
        String ext = outputExtensionOf(converted);
        List<String> proxied = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String query = resourceSigner.signQuery(taskNo, i);
            if (query == null) {
                // fail-closed: 密钥缺失时清空, 配置恢复后由对账兜底重放终态事件重建
                log.error("[Resource] resource-sign-key 缺失, 资源转代理 URL 失败: taskNo={}", taskNo);
                converted.put("resources", List.of());
                return converted;
            }
            // 后缀仅可读性 (Web 直链语义), 代理侧剥离后验签 — 与无后缀 URL 同 sig 通用
            proxied.add(PROXY_PREFIX + taskNo + "/" + i + ext + "?" + query);
        }
        converted.put("resources", proxied);
        return converted;
    }

    /**
     * 读时转换 (issue #43 存储格式翻转对偶): 终态条目 result 逐元素判定 —
     * <ul>
     *   <li>已是 {@code /v1/resources/{taskNo}/{i}} 代理路径 (<b>存量旧格式条目</b>) →
     *       剥查询串按路径内 task/index 重签新鲜 exp+sig (24h 旧签名自愈), 路径后缀保留;</li>
     *   <li>原始 http(s)/data: URL (新格式) → 与 {@link #convert} 同逻辑现签.</li>
     * </ul>
     * 两种形态输出都是新鲜签名; 密钥缺失对原始值 fail-closed 清空 (同 convert),
     * 对旧代理路径原样保留 (验签同样不可用, 无额外损失).
     */
    public Map<String, Object> convertAtRead(String taskNo, Map<String, Object> result) {
        Map<String, Object> converted = result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
        Object resources = converted.get("resources");
        if (!(resources instanceof List<?> list)) {
            return converted;
        }
        String ext = outputExtensionOf(converted);
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (v instanceof String s && s.startsWith(PROXY_PREFIX)) {
                out.add(resign(s));
                continue;
            }
            String query = resourceSigner.signQuery(taskNo, i);
            if (query == null) {
                log.error("[Resource] resource-sign-key 缺失, 读时转换失败: taskNo={}", taskNo);
                converted.put("resources", List.of());
                return converted;
            }
            out.add(PROXY_PREFIX + taskNo + "/" + i + ext + "?" + query);
        }
        converted.put("resources", out);
        return converted;
    }

    /** 旧格式代理 URL 重签: 剥查询串, 按路径内 task/index 现签, 保留 .{ext} 可读性后缀. */
    private String resign(String proxyUrl) {
        int q = proxyUrl.indexOf('?');
        String path = q >= 0 ? proxyUrl.substring(0, q) : proxyUrl;
        String[] parts = path.split("/");
        // ["", "v1", "resources", taskNo, "{index}[.{ext}]"]
        if (parts.length != 5) {
            log.warn("[Resource] 旧格式代理路径畸形, 原样返回: {}", proxyUrl);
            return proxyUrl;
        }
        int index = parseIndexSegment(parts[4]);
        if (index < 0) {
            log.warn("[Resource] 旧格式代理路径 index 非法, 原样返回: {}", proxyUrl);
            return proxyUrl;
        }
        String query = resourceSigner.signQuery(parts[3], index);
        if (query == null) {
            // 密钥缺失: 旧签名验签同样不可用, 原样保留不额外损失
            return proxyUrl;
        }
        return path + "?" + query;
    }

    /** "{index}[.{ext}]" 路径段解析 (与 ResourceProxyController.parseIndex 同口径). */
    private static int parseIndexSegment(String file) {
        String f = file == null ? "" : file.trim();
        int dot = f.lastIndexOf('.');
        if (dot > 0) {
            String ext = f.substring(dot + 1);
            if (!ext.isEmpty() && ext.chars().allMatch(Character::isLetterOrDigit)) {
                f = f.substring(0, dot);
            }
        }
        try {
            int idx = Integer.parseInt(f);
            return idx >= 0 ? idx : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 扩展名取 usage.outputType (resultMapping 约定: png/jpg/...), 缺省无后缀 (旧形态不变). */
    private static String outputExtensionOf(Map<String, Object> result) {
        Object usage = result.get("usage");
        if (usage instanceof Map<?, ?> u && u.get("outputType") != null) {
            String t = String.valueOf(u.get("outputType")).trim().toLowerCase();
            if (!t.isEmpty() && t.chars().allMatch(Character::isLetterOrDigit) && t.length() <= 5) {
                return "." + t;
            }
        }
        return "";
    }
}
