package fun.commons.tokengateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM 上游请求头透传白名单 (gateway.upstream.*, issue #32).
 *
 * <p>默认空 = 一个头都不透传 (fail-closed), 出站请求与历史版本逐字节一致.
 * 配置后, 命中白名单的<strong>客户端请求头</strong>会被追加到网关发往 LLM 上游的
 * 全部请求上 (SSE 流式 + 非流式, 双协议), 用途如 mock 上游故障注入:
 *
 * <pre>
 * gateway:
 *   upstream:
 *     passthrough-headers:      # 条目两种形态
 *       - X-Mock-Fault          # 精确名
 *       - X-Mock-*              # 前缀通配 (以 * 结尾)
 * </pre>
 *
 * <p>安全语义:
 * <ul>
 *   <li>黑名单硬编码在 {@link #BLACKLIST_EXACT}/{@link #BLACKLIST_PREFIXES},
 *       优先级高于白名单 —— 配置了也不透传 (防内部凭证/签名头泄漏给第三方上游)</li>
 *   <li>大小写不敏感匹配 (HTTP 头名 case-insensitive)</li>
 *   <li>多值头只取第一个值 (防请求走私)</li>
 *   <li>协议头 (Accept / anthropic-version / x-api-key) 在黑名单内, 白名单顶不掉</li>
 * </ul>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway.upstream")
public class UpstreamPassthroughProperties {

    /**
     * 黑名单-精确名 (小写). 即使配置进白名单也绝不透传给上游.
     *
     * <p>来源核对 (issue #32):
     * <ul>
     *   <li>HTTP 语义头: Authorization / Cookie / Set-Cookie / Host / Content-Length</li>
     *   <li>内部鉴权头 —— 抄自 gateway-core/rpc/RpcInternalAuth.java:
     *       KEY 式发 {@code X-API-Key}, JWT 式发 {@code X-Internal-Token}
     *       (另由 {@link #BLACKLIST_PREFIXES} 的 {@code X-Internal-*} 前缀兜底)</li>
     *   <li>invoker 协议头 (SsePassthroughInvoker / 各 controller 出站构造点自己要设的):
     *       Accept / anthropic-version / x-api-key —— 防白名单顶掉协议头导致上游 4xx</li>
     * </ul>
     */
    private static final List<String> BLACKLIST_EXACT = List.of(
            "authorization",
            "cookie",
            "set-cookie",
            "host",
            "content-length",
            // RpcInternalAuth (gateway-core/rpc/RpcInternalAuth.java) 内部鉴权/签名头
            "x-api-key",
            "x-internal-token",
            // invoker 自身协议头 (防白名单覆盖)
            "accept",
            "anthropic-version");

    /** 黑名单-前缀 (小写, 含连字符). X-Internal-* 内部签名头族整体封禁. */
    private static final List<String> BLACKLIST_PREFIXES = List.of(
            "x-internal-");

    /** 透传白名单: 精确名 (X-Mock-Fault) 或前缀通配 (X-Mock-*). 默认空 = 不透传任何头. */
    private List<String> passthroughHeaders = new ArrayList<>();

    /**
     * 单个客户端头是否在黑名单内 (大小写不敏感).
     * 黑名单优先级高于白名单: 命中即绝不透传, 配置了也一样.
     */
    public boolean isBlacklisted(@Nullable String headerName) {
        if (headerName == null) {
            return true;
        }
        String lower = headerName.toLowerCase(Locale.ROOT);
        if (BLACKLIST_EXACT.contains(lower)) {
            return true;
        }
        for (String prefix : BLACKLIST_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单个客户端头是否可透传: 命中白名单 (精确名或前缀通配, 大小写不敏感) 且不在黑名单.
     */
    public boolean isAllowed(@Nullable String headerName) {
        if (headerName == null || isBlacklisted(headerName)) {
            return false;
        }
        String lower = headerName.toLowerCase(Locale.ROOT);
        for (String entry : passthroughHeaders) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.endsWith("*")) {
                if (lower.startsWith(e.substring(0, e.length() - 1))) {
                    return true;
                }
            } else if (lower.equals(e)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从客户端请求头解析出可透传头 (每头只取第一个值, 防走私; 保持客户端头顺序).
     * 白名单为空 (零配置) 时恒返回空 Map —— 出站请求与历史版本逐字节一致.
     */
    public Map<String, String> resolve(@Nullable HttpHeaders clientHeaders) {
        Map<String, String> out = new LinkedHashMap<>();
        if (clientHeaders == null || clientHeaders.isEmpty() || passthroughHeaders.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, List<String>> en : clientHeaders.entrySet()) {
            String name = en.getKey();
            if (name == null || name.isBlank() || !isAllowed(name)) {
                continue;
            }
            List<String> values = en.getValue();
            if (values == null || values.isEmpty()) {
                continue;
            }
            out.put(name, values.get(0));
        }
        return out;
    }

    /**
     * 便捷入口: 把客户端可透传头追加到 WebClient 出站请求.
     * 调用约定: 协议头 (Authorization/x-api-key/anthropic-version/Accept) 先设, 再调本方法 ——
     * 追加头已在黑名单内挡掉同名协议头, 不会顶掉.
     */
    public void applyTo(WebClient.RequestHeadersSpec<?> spec, @Nullable HttpHeaders clientHeaders) {
        resolve(clientHeaders).forEach(spec::header);
    }
}
