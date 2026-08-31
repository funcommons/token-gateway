package fun.commons.tokengateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway WebFlux 配置 (从 application.yml 注入).
 *
 * <pre>
 * gateway:
 *   backend:
 *     url: http://localhost:9400     # 主应用 RPC 目标
 *     internal-token: dev-internal-token  # 服务间鉴权
 *     timeout: 10s                   # WebClient 超时
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.backend")
public class GatewayProperties {

    /** 主应用 URL (RPC 目标) */
    private String url = "http://localhost:9400";

    /** 服务间鉴权 token (X-Internal-Token 头) */
    private String internalToken = "";

    /** WebClient 超时 */
    private java.time.Duration timeout = java.time.Duration.ofSeconds(10);
}
