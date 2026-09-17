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
 * 上游 resources → 网关代理 URL 转换 (webhook 终态与 poll 透传两路共用).
 *
 * <p>《05》§4 永不透传: 上游 URL 只允许以 /v1/resources/{taskNo}/{idx}?exp=&amp;sig=
 * 代理形态出现; 签名密钥缺失时 fail-closed 清空 resources (对账兜底重放重建).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResourceUrlConverter {

    private final ResourceSigner resourceSigner;

    /** 返回 result 副本, resources 已就地转换为代理 URL. */
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
            proxied.add("/v1/resources/" + taskNo + "/" + i + ext + "?" + query);
        }
        converted.put("resources", proxied);
        return converted;
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
