package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 后端鉴权三式发件器 (G1/G3: 面感知 + env 化凭证, 设计方案 §5.2).
 *
 * <p>策略 (按能力面 EndpointConfig.auth):
 * <ul>
 *   <li>{@code none} → 不发鉴权头 (仅 localhost/sidecar; 非 localhost 由 CapabilityValidator 告警)</li>
 *   <li>{@code key} → {@code X-API-Key: <key>} (静态共享密钥, 内网限定)</li>
 *   <li>{@code jwt} → {@code X-Internal-Token: <jwtSecret>} (现网 internal-token 形态;
 *       jwtSecret 配置预铸 JWT —— 后端换 secret 须同步重铸, env 注入禁入仓)</li>
 * </ul>
 *
 * <p>{@code dev-} 前缀约定保留 (dev 模式 backend require-signed-header=false,
 * 发未签名 token 会被误判为 malformed)。
 *
 * <p>兼容窗口: 旧 {@link #attachTo(WebClient.RequestHeadersSpec)} 走平移态
 * internal-token 三态语义, 供未迁移调用方使用。
 */
@Component
@RequiredArgsConstructor
public class RpcInternalAuth {

    private final GatewayProperties props;

    /** 面感知: 按能力面鉴权式附加头. */
    public void attachTo(WebClient.RequestHeadersSpec<?> spec, EndpointConfig endpoint) {
        spec.headers(h -> applyTo(h, endpoint));
    }

    /** 按能力面鉴权式写入 HttpHeaders (WebClient headers() lambda 场景). */
    public void applyTo(org.springframework.http.HttpHeaders headers, EndpointConfig endpoint) {
        AuthType auth = endpoint.getAuth() == null ? AuthType.NONE : endpoint.getAuth();
        switch (auth) {
            case NONE -> {
                // 不发鉴权头
            }
            case KEY -> {
                if (endpoint.getKey() != null && !endpoint.getKey().isBlank()) {
                    headers.set("X-API-Key", endpoint.getKey());
                }
            }
            case JWT -> {
                String secret = endpoint.getJwtSecret();
                if (secret != null && !secret.isBlank() && !secret.startsWith("dev-")) {
                    headers.set("X-Internal-Token", secret);
                }
            }
        }
    }

    /**
     * 是否应发送 X-Internal-Token 头 (平移态兼容语义).
     */
    public boolean shouldAttach() {
        String token = props.getInternalToken();
        if (token == null || token.isBlank()) {
            return false;
        }
        return !token.startsWith("dev-");
    }

    /**
     * 条件附加 X-Internal-Token 头 (平移态兼容语义, G1 迁移完成后删除).
     */
    public void attachTo(WebClient.RequestHeadersSpec<?> spec) {
        if (!shouldAttach()) {
            return;
        }
        spec.header("X-Internal-Token", props.getInternalToken());
    }
}
