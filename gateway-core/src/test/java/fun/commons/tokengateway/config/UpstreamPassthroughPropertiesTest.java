package fun.commons.tokengateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UpstreamPassthroughProperties 白名单匹配测试 (issue #32).
 *
 * <p>覆盖: 精确命中 / 前缀通配 / 黑名单压过白名单 / 大小写不敏感 / 多值取一 /
 * 零配置 fail-closed.
 */
@DisplayName("UpstreamPassthroughProperties 白名单匹配")
class UpstreamPassthroughPropertiesTest {

    private UpstreamPassthroughProperties props(String... entries) {
        UpstreamPassthroughProperties p = new UpstreamPassthroughProperties();
        p.getPassthroughHeaders().addAll(java.util.Arrays.asList(entries));
        return p;
    }

    private HttpHeaders clientHeaders(String name, String... values) {
        HttpHeaders h = new HttpHeaders();
        for (String v : values) {
            h.add(name, v);
        }
        return h;
    }

    @Test
    @DisplayName("零配置: 默认空白名单 → 什么都不透传 (fail-closed 回归红线)")
    void defaultEmptyIsFailClosed() {
        UpstreamPassthroughProperties p = new UpstreamPassthroughProperties();
        HttpHeaders h = clientHeaders("X-Mock-Fault", "stream-cut-after=2");
        h.add("X-Custom-Trace", "t1");
        assertThat(p.resolve(h)).isEmpty();
        assertThat(p.isAllowed("X-Mock-Fault")).isFalse();
    }

    @Test
    @DisplayName("精确命中: 配置 X-Mock-Fault → 透传该头, 未配置的不透传")
    void exactMatch() {
        UpstreamPassthroughProperties p = props("X-Mock-Fault");
        HttpHeaders h = clientHeaders("X-Mock-Fault", "stream-cut-after=2");
        h.add("X-Not-Listed", "v");
        Map<String, String> resolved = p.resolve(h);
        assertThat(resolved).containsExactly(java.util.Map.entry("X-Mock-Fault", "stream-cut-after=2"));
    }

    @Test
    @DisplayName("前缀通配: X-Mock-* 命中 X-Mock-Fault / X-Mock-Drop, 不命中 XMock")
    void prefixWildcardMatch() {
        UpstreamPassthroughProperties p = props("X-Mock-*");
        HttpHeaders h = clientHeaders("X-Mock-Fault", "cut");
        h.add("X-Mock-Drop", "conn");
        h.add("XMock-Other", "no");
        h.add("X-Other", "no");
        assertThat(p.resolve(h))
                .containsOnlyKeys("X-Mock-Fault", "X-Mock-Drop");
    }

    @Test
    @DisplayName("黑名单压过白名单: Authorization / Cookie / x-api-key 配置了也不透传")
    void blacklistBeatsWhitelist() {
        UpstreamPassthroughProperties p = props("Authorization", "Cookie", "Set-Cookie",
                "x-api-key", "Host", "Content-Length", "Accept", "anthropic-version");
        HttpHeaders h = clientHeaders("Authorization", "Bearer sk-internal-secret");
        h.add("Cookie", "session=abc");
        h.add("Set-Cookie", "a=1");
        h.add("x-api-key", "key");
        h.add("Host", "evil.example");
        h.add("Content-Length", "7");
        h.add("Accept", "text/html");
        h.add("anthropic-version", "2099-01-01");
        assertThat(p.resolve(h)).isEmpty();
    }

    @Test
    @DisplayName("黑名单前缀: X-Internal-* 族 (含 RpcInternalAuth 的 X-Internal-Token) 绝不透传")
    void internalPrefixAlwaysBlocked() {
        UpstreamPassthroughProperties p = props("X-Internal-Token", "X-Internal-*",
                "X-Internal-Request-Id");
        HttpHeaders h = clientHeaders("X-Internal-Token", "jwt-secret");
        h.add("X-Internal-Request-Id", "rid-1");
        h.add("x-internal-token", "lowercase-secret");
        assertThat(p.resolve(h)).isEmpty();
        assertThat(p.isBlacklisted("X-Internal-Anything")).isTrue();
    }

    @Test
    @DisplayName("大小写不敏感: 配置小写命中客户端大写头, 透传保留客户端原头名")
    void caseInsensitiveMatchPreservesClientCasing() {
        UpstreamPassthroughProperties p = props("x-mock-fault", "X-MOCK-DROP");
        HttpHeaders h = clientHeaders("X-Mock-Fault", "cut-after=2");
        h.add("x-mock-drop", "conn");
        assertThat(p.resolve(h))
                .containsEntry("X-Mock-Fault", "cut-after=2")
                .containsEntry("x-mock-drop", "conn");
    }

    @Test
    @DisplayName("多值头只取第一个值 (防走私)")
    void multiValueTakesFirstOnly() {
        UpstreamPassthroughProperties p = props("X-Mock-Fault");
        HttpHeaders h = clientHeaders("X-Mock-Fault", "first", "second", "third");
        assertThat(p.resolve(h)).containsExactly(java.util.Map.entry("X-Mock-Fault", "first"));
    }

    @Test
    @DisplayName("isAllowed 单点判定: 白名单命中且不在黑名单才 true")
    void isAllowedPointCheck() {
        UpstreamPassthroughProperties p = props("X-Mock-*", "Authorization");
        assertThat(p.isAllowed("x-mock-fault")).isTrue();
        assertThat(p.isAllowed("X-MOCK-OTHER")).isTrue();
        assertThat(p.isAllowed("X-Mock")).isFalse();
        assertThat(p.isAllowed("Authorization")).isFalse();
        assertThat(p.isAllowed(null)).isFalse();
        assertThat(p.isAllowed("X-Unrelated")).isFalse();
    }

    @Test
    @DisplayName("空白客户端头 / 空白配置条目 安全跳过")
    void blankEntriesAndNullHeadersSkipped() {
        UpstreamPassthroughProperties p = props("X-Mock-Fault", "", "  ", null);
        assertThat(p.resolve(null)).isEmpty();
        assertThat(p.resolve(new HttpHeaders())).isEmpty();
        assertThat(p.resolve(clientHeaders("X-Mock-Fault", "v"))).isNotEmpty();
    }
}
