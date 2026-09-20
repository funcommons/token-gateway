package fun.commons.tokengateway.util;

import fun.commons.tokengateway.config.ClientIpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 客户端 IP 解析器 (issue #27): token-validate 的 clientIp 填充.
 *
 * <p>背景: X-Forwarded-For (XFF) 首段由客户端任意伪造, 无脑取首位 = IP 白名单/风控被绕过.
 * 策略 (安全契约「客户端 IP 解析」节):
 * <ul>
 *   <li>trusted-proxies=0 (默认): 不信任任何代理头, 恒取 TCP 对端地址 (防伪造核心)</li>
 *   <li>trusted-proxies=N>0: 前面有 N 层可信代理. XFF 追加规则 = 每跳代理把「它看到的
 *       对端」追加到尾部, 故最右 N 段由可信代理逐跳追加, 客户端 IP = 从右数第 N 段
 *       (即 parts[length-N]; trusted=1 时即最右段)</li>
 *   <li>XFF 缺失 / 段数耗尽 (段数 &lt; N) / 命中段为空 (畸形) → 回退 TCP 对端地址</li>
 *   <li>TCP 对端为 null → 连接来源不明, 拒绝解析 XFF, 返回 null (fail-safe)</li>
 *   <li>enabled=false: 不做 XFF 信任解析, 恒取 TCP 对端 (fail-safe)</li>
 * </ul>
 *
 * <p>核心解析为静态纯函数 ({@link #resolve(String, InetSocketAddress, int)}), 单测直测;
 * 实例入口供 controller 层注入使用 ({@code @Component}, util 包在扫描白名单内).
 */
@Component
@RequiredArgsConstructor
public class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final ClientIpProperties props;

    /**
     * 实例入口: 按 gateway.client-ip.* 配置解析请求的客户端 IP.
     *
     * @return 客户端 IP (可能为 null: 无 XFF 且 TCP 对端地址缺失, 如测试直调)
     */
    public String resolve(ServerWebExchange exchange) {
        if (!props.isEnabled()) {
            return addressOf(exchange.getRequest().getRemoteAddress());
        }
        List<String> xff = exchange.getRequest().getHeaders().get(FORWARDED_FOR_HEADER);
        // 多个 XFF 头按 RFC 7239 前身惯例合并后统一解析 (代理逐层 append)
        String joined = xff == null ? null : String.join(",", xff);
        return resolve(joined, exchange.getRequest().getRemoteAddress(), props.getTrustedProxies());
    }

    /**
     * 纯函数核心: XFF 值 + TCP 对端地址 + 可信代理层数 → 客户端 IP.
     *
     * @param forwardedFor  X-Forwarded-For 头值 (逗号分隔, 可为 null)
     * @param remoteAddress TCP 对端地址 (可为 null)
     * @param trustedProxies 可信代理层数 (<=0 视为不信任任何代理头)
     */
    public static String resolve(String forwardedFor, InetSocketAddress remoteAddress, int trustedProxies) {
        if (remoteAddress == null) {
            // 连接来源不明: 任何 XFF 都不可信 (fail-safe)
            return null;
        }
        if (trustedProxies <= 0 || forwardedFor == null || forwardedFor.isBlank()) {
            return addressOf(remoteAddress);
        }
        String[] parts = forwardedFor.split(",");
        int idx = parts.length - trustedProxies;
        if (idx < 0) {
            // 可信层数 > XFF 段数: 没有可信代理之外的段可取 (耗尽) → 回退对端
            return addressOf(remoteAddress);
        }
        String candidate = parts[idx].trim();
        return candidate.isEmpty() ? addressOf(remoteAddress) : candidate;
    }

    /** 对端地址 → IP 字符串 (IPv4/IPv6 均不带端口; 未解析地址用 host 字符串兜底). */
    private static String addressOf(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        return remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress()
                : remoteAddress.getHostString();
    }
}
