package fun.commons.tokengateway.upstream;

import fun.commons.tokengateway.contract.DistributeVO;

import java.util.HashMap;
import java.util.Map;

/**
 * 渠道模型映射应用 (渠道 model_mapping: 请求 model_code → 上游 upstream_code).
 * <p>发上游前必须替换 body.model, 否则配置了重映射的渠道会收到上游不存在的模型名.
 * <p>对齐 MMagiX gateway-webflux UpstreamModelMapper (移植).
 *
 * @author system
 */
public final class UpstreamModelMapper {

    private UpstreamModelMapper() {
    }

    /**
     * 解析上游模型名: modelMapping 命中则替换, 否则原样返回.
     */
    public static String resolveUpstreamModel(DistributeVO channel, String requestedModel) {
        if (channel == null || channel.getModelMapping() == null || requestedModel == null) {
            return requestedModel;
        }
        return channel.getModelMapping().getOrDefault(requestedModel, requestedModel);
    }

    /**
     * 返回替换 model 后的副本; 无需替换时原样返回.
     */
    public static Map<String, Object> applyModelMapping(DistributeVO channel, Map<String, Object> body) {
        if (body == null || !body.containsKey("model")) {
            return body;
        }
        Object current = body.get("model");
        String mapped = resolveUpstreamModel(channel, current == null ? null : String.valueOf(current));
        if (mapped == null || mapped.equals(current)) {
            return body;
        }
        Map<String, Object> copy = new HashMap<>(body);
        copy.put("model", mapped);
        return copy;
    }
}
