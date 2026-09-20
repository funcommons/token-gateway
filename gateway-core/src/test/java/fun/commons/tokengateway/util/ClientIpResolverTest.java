package fun.commons.tokengateway.util;

import fun.commons.tokengateway.config.ClientIpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClientIpResolver 单测 (issue #27): 纯函数核心 + 实例入口 (含 enabled/trusted 配置分叉).
 *
 * <p>核心安全用例: 伪造首位 XFF + trusted=0 → 必须取 TCP 对端, 不吃伪造值.
 */
@DisplayName("ClientIpResolver")
class ClientIpResolverTest {

    private static final InetSocketAddress REMOTE =
            new InetSocketAddress("10.0.0.7", 51234);
    private static final InetSocketAddress PROXY_REMOTE =
            new InetSocketAddress("172.17.0.9", 40000);

    // ===== 纯函数核心 =====

    @Test
    @DisplayName("无 XFF → 取 TCP 对端地址 (不带端口)")
    void noXffFallsBackToRemote() {
        assertThat(ClientIpResolver.resolve(null, REMOTE, 0)).isEqualTo("10.0.0.7");
        assertThat(ClientIpResolver.resolve(null, REMOTE, 2)).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("伪造首位 XFF + trusted=0 → 必须取 TCP 对端 (防伪造核心)")
    void spoofedXffWithZeroTrustedIgnored() {
        assertThat(ClientIpResolver.resolve("1.2.3.4, 10.0.0.1", REMOTE, 0))
                .isEqualTo("10.0.0.7");
        assertThat(ClientIpResolver.resolve("1.2.3.4", REMOTE, 0))
                .isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("trusted=1: 取最右段 (可信代理追加的对端 = 客户端真实 IP)")
    void oneTrustedProxy() {
        // 链路 client(10.0.0.1) → proxy1 → 网关: proxy1 append 10.0.0.1, 首段是客户端伪造的
        assertThat(ClientIpResolver.resolve("1.2.3.4, 10.0.0.1", PROXY_REMOTE, 1))
                .isEqualTo("10.0.0.1");
        // 单段 XFF (无伪造): proxy1 append 的即真实客户端
        assertThat(ClientIpResolver.resolve("10.0.0.1", PROXY_REMOTE, 1))
                .isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("trusted=2: 从右数第 2 段 (两层可信代理逐跳追加, 客户端在倒数第 2)")
    void twoTrustedProxies() {
        // 链路 client(10.0.0.1) → proxy1(172.17.0.2) → proxy2 → 网关:
        // proxy2 append 172.17.0.2, proxy1 append 10.0.0.1, 首段伪造
        assertThat(ClientIpResolver.resolve("1.2.3.4, 10.0.0.1, 172.17.0.2", PROXY_REMOTE, 2))
                .isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("XFF 段数 < 可信层数 (耗尽) → 回退 TCP 对端; 段数=可信层数 → 取最左段")
    void exhaustedXffFallsBackToRemote() {
        assertThat(ClientIpResolver.resolve("onlyone", PROXY_REMOTE, 2))
                .isEqualTo("172.17.0.9");
        // 段数恰 = 可信层数: 最左段也由可信链产生 (proxy1 追加的真实客户端), 可采信
        assertThat(ClientIpResolver.resolve("a, b", PROXY_REMOTE, 2))
                .isEqualTo("a");
    }

    @Test
    @DisplayName("畸形 XFF (空白/命中空段) → 回退 TCP 对端")
    void malformedXffFallsBackToRemote() {
        assertThat(ClientIpResolver.resolve("   ", REMOTE, 1)).isEqualTo("10.0.0.7");
        // 命中段为纯空白 (畸形) 不可作为 IP 采信
        assertThat(ClientIpResolver.resolve("a, ", REMOTE, 1)).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("remoteAddress 为 null → 恒返回 null (连接来源不明, XFF 一律不可信)")
    void nullRemoteReturnsNull() {
        assertThat(ClientIpResolver.resolve(null, null, 0)).isNull();
        assertThat(ClientIpResolver.resolve("client, proxy1", null, 1)).isNull();
    }

    @Test
    @DisplayName("IPv6 对端地址取裸地址 (不含端口/括号)")
    void ipv6RemoteAddress() {
        InetSocketAddress v6 = new InetSocketAddress("0:0:0:0:0:0:0:1", 50000);
        String ip = ClientIpResolver.resolve(null, v6, 0);
        assertThat(ip).isNotBlank()
                .doesNotContain("[").doesNotContain("]")
                .doesNotContain(String.valueOf(50000))
                .endsWith("1");
    }

    // ===== 实例入口 (配置分叉) =====

    @Test
    @DisplayName("实例入口: 默认配置 (enabled=true, trusted=0) 恒取 TCP 对端")
    void instanceDefaultsToRemote() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/messages")
                        .remoteAddress(REMOTE)
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1"));
        assertThat(new ClientIpResolver(new ClientIpProperties()).resolve(exchange))
                .isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("实例入口: enabled=false → 恒取 TCP 对端, XFF 完全忽略")
    void instanceDisabledIgnoresXff() {
        ClientIpProperties props = new ClientIpProperties();
        props.setEnabled(false);
        props.setTrustedProxies(3);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/messages")
                        .remoteAddress(REMOTE)
                        .header("X-Forwarded-For", "1.2.3.4, 10.0.0.1"));
        assertThat(new ClientIpResolver(props).resolve(exchange)).isEqualTo("10.0.0.7");
    }

    @Test
    @DisplayName("实例入口: trusted=N 生效 + 多个 XFF 头合并解析")
    void instanceTrustedProxiesAndMultipleHeaders() {
        ClientIpProperties props = new ClientIpProperties();
        props.setTrustedProxies(2);
        // 两个 XFF 头 (代理逐层 append): 合并 = "9.9.9.9, 10.8.0.1, 10.8.0.2"
        // → 从右数第 2 段 = 10.8.0.1 (client 真实 IP), 首段伪造被跳过
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/messages")
                        .remoteAddress(REMOTE)
                        .header("X-Forwarded-For", "9.9.9.9, 10.8.0.1")
                        .header("X-Forwarded-For", "10.8.0.2"));
        assertThat(new ClientIpResolver(props).resolve(exchange)).isEqualTo("10.8.0.1");
    }
}
