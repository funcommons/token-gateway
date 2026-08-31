package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 内部 token 鉴权策略 (dev/prod 兼容).
 *
 * <p>从 HttpTokenApi / HttpChannelApi 各自的实现抽到公共类, 避免重复.
 *
 * <p>策略:
 * <ul>
 *   <li>token 为 null/空白 → 不发头 (走 backend legacy 兼容路径)</li>
 *   <li>token 以 {@code dev-} 开头 → 不发头 (dev 模式 backend {@code require-signed-header=false},
 *       发未签名 token 会被误判为 malformed)</li>
 *   <li>其它 (看起来是真实签名 token) → 发 {@code X-Internal-Token} 头</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class RpcInternalAuth {

    private final GatewayProperties props;

    /**
     * 是否应发送 X-Internal-Token 头.
     */
    public boolean shouldAttach() {
        String token = props.getInternalToken();
        if (token == null || token.isBlank()) {
            return false;
        }
        return !token.startsWith("dev-");
    }

    /**
     * 条件附加 X-Internal-Token 头到 WebClient spec.
     */
    public void attachTo(WebClient.RequestHeadersSpec<?> spec) {
        if (!shouldAttach()) {
            return;
        }
        spec.header("X-Internal-Token", props.getInternalToken());
    }
}
