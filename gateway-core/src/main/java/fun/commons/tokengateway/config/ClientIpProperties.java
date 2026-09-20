package fun.commons.tokengateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 客户端 IP 解析配置 (gateway.client-ip.*, issue #27).
 *
 * <p>token-validate 的 clientIp 填充策略——能力面拿到的"调用方 IP"是否可信,
 * 取决于网关前面有几层可信反向代理 (LB / Ingress):
 *
 * <pre>
 * gateway:
 *   client-ip:
 *     enabled: true          # false = 不做 XFF 信任解析, 恒取 TCP 对端 (fail-safe)
 *     trusted-proxies: 0     # 0 = 不信任任何 X-Forwarded-For (恒取 TCP 对端, 防伪造)
 *                            # N = 前面有 N 层可信代理, XFF 从右往左跳过 N 个后取第一个
 * </pre>
 *
 * <p>策略细则见 docs/开发文档/04_后端服务对接安全契约方案.md「客户端 IP 解析」节。
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.client-ip")
public class ClientIpProperties {

    /** 是否启用 XFF 信任解析 (false 时 clientIp 恒取 TCP 对端地址, X-Forwarded-For 完全忽略) */
    private boolean enabled = true;

    /** 可信反向代理层数 (0 = 不信任任何代理头, 恒取 TCP 对端; 大于 XFF 段数视为耗尽, 回退对端) */
    private int trustedProxies = 0;
}
