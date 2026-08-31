package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 模型列表 HTTP RPC (调主应用 /api/v1/internal/chat-models 端点).
 *
 * <p>返回内部结构: ApiResponse&lt;List&lt;{id, displayName, owner}&gt;&gt;.
 * Controller 按客户端协议 (OpenAI/Anthropic) 再次组装 shape.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpChatModelApi {

    private static final ParameterizedTypeReference<ApiResponse<List<Map<String, Object>>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient.Builder webClientBuilder;
    private final GatewayProperties props;
    private final RpcInternalAuth internalAuth;

    /**
     * 拉取启用中的模型列表 (主应用按 code 去重 + catalog 元数据已合并).
     *
     * @param groupId 订阅分组 ID, 非空时按分组范围过滤 (§3.1 路由约束); null 返所有启用模型
     */
    public Mono<ApiResponse<List<Map<String, Object>>>> listEnabledModels(Long groupId) {
        String baseUri = props.getUrl() + "/api/v1/internal/chat-models";
        String uri = groupId != null ? baseUri + "?groupId=" + groupId : baseUri;
        WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().get()
                .uri(uri);
        internalAuth.attachTo(req);
        return req.retrieve()
                .bodyToMono(LIST_TYPE)
                .timeout(props.getTimeout())
                .doOnError(e -> log.error("[HttpChatModelApi] listEnabledModels RPC 失败: groupId={}, err={}",
                        groupId, e.getMessage()))
                .onErrorResume(e -> Mono.just(ApiResponse.fail(
                        fun.commons.tokengateway.framework.ApiCode.SERVICE_TIMEOUT.getCode(),
                        "chat-model RPC failed: " + e.getMessage())));
    }
}
