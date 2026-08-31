package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.contract.TokenValidateRequest;
import fun.commons.tokengateway.rpc.HttpChatModelApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型列表端点 (WebFlux 原生).
 *
 * <p>路径: GET /v1/models
 * <p>鉴权: Authorization Bearer 或 x-api-key 双头, 走 TokenApi.validate
 * <p>数据源: 主应用 /api/v1/internal/chat-models RPC (channel_model 去重 + catalog 元数据)
 * <p>响应 shape: 按 anthropic-version 头切换
 * <ul>
 *   <li>OpenAI 客户端: {object:"list", data:[{id, object:"model", created, owned_by, supported_endpoint:"chat"}]}</li>
 *   <li>Anthropic 客户端: {data:[{id, type:"model", display_name}], first_id, last_id, has_more:false}</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ModelsController {

    private final HttpTokenApi tokenApi;
    private final HttpChatModelApi chatModelApi;

    @GetMapping("/v1/models")
    public Mono<Object> listModels(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "x-api-key", required = false) String xApiKey,
            @RequestHeader(value = "anthropic-version", required = false) String anthropicVersion
    ) {
        String apiKey = extractApiKey(authorization, xApiKey);
        if (apiKey == null) {
            return Mono.error(new RelayException(401,
                    fun.commons.tokengateway.framework.ApiCode.UNAUTHORIZED.getCode(),
                    "缺少 bearer token"));
        }
        return tokenApi.validate(TokenValidateRequest.builder().apiKey(apiKey).model(null).build())
                .flatMap(tokenResp -> {
                    if (tokenResp == null || !tokenResp.isSuccess() || tokenResp.getData() == null
                            || !tokenResp.getData().isValid()) {
                        return Mono.error(new RelayException(401, "invalid token"));
                    }
                    boolean isAnthropic = (anthropicVersion != null && !anthropicVersion.isBlank())
                            || (xApiKey != null && !xApiKey.isBlank());
                    Long groupId = parseLong(tokenResp.getData().getGroupId());
                    return chatModelApi.listEnabledModels(groupId)
                            .map(modelResp -> {
                                List<Map<String, Object>> models = (modelResp != null && modelResp.isSuccess()
                                        && modelResp.getData() != null)
                                        ? modelResp.getData() : List.of();
                                return isAnthropic ? anthropicShape(models) : openaiShape(models);
                            });
                });
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractApiKey(String auth, String xApiKey) {
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        if (xApiKey != null && !xApiKey.isBlank()) {
            return xApiKey.trim();
        }
        return null;
    }

    private static Map<String, Object> openaiShape(List<Map<String, Object>> models) {
        long created = Instant.now().getEpochSecond();
        List<Map<String, Object>> data = new ArrayList<>(models.size());
        for (Map<String, Object> m : models) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", m.get("id"));
            entry.put("object", "model");
            entry.put("created", created);
            entry.put("owned_by", m.getOrDefault("owner", "unknown"));
            entry.put("supported_endpoint", "chat");
            data.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", "list");
        result.put("data", data);
        return result;
    }

    private static Map<String, Object> anthropicShape(List<Map<String, Object>> models) {
        List<Map<String, Object>> data = new ArrayList<>(models.size());
        String firstId = null;
        String lastId = null;
        for (Map<String, Object> m : models) {
            String id = String.valueOf(m.get("id"));
            if (firstId == null) {
                firstId = id;
            }
            lastId = id;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", id);
            entry.put("type", "model");
            entry.put("display_name", m.getOrDefault("displayName", id));
            data.add(entry);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("data", data);
        result.put("first_id", firstId);
        result.put("last_id", lastId);
        result.put("has_more", false);
        return result;
    }
}
