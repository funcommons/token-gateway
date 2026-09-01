package fun.commons.tokengateway.worker.script;

import com.alibaba.fastjson2.JSON;
import fun.commons.tokengateway.worker.config.WorkerProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 脚本 http binding (《05》§9.5: 出网白名单 + 超时收口; 脚本唯一的网络出口).
 *
 * <p>白名单为空 = 全禁 (fail-closed); 匹配按 url 前缀或 host 相等.
 * 同步阻塞返回 (脚本模型是阻塞式的; 线程由 WorkerLoop 的虚拟线程提供).
 */
@Component
public class ScriptHttpClient {

    private final WebClient webClient;
    private final List<String> egressAllowlist;
    private final Duration timeout;

    public ScriptHttpClient(WebClient.Builder builder, WorkerProperties props) {
        this.webClient = builder.build();
        this.egressAllowlist = props.getEgressAllowlist();
        this.timeout = props.getHookTimeout();
    }

    /** 响应视图: {status, body(解析后 Map/List 或原文), ok}. */
    public record Resp(int status, Object body, boolean ok) {
    }

    public Resp get(Object url, Map<?, ?> headers) {
        String u = String.valueOf(url);
        checkEgress(u);
        WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(u);
        applyHeaders(spec, headers);
        return exchange(spec);
    }

    public Resp post(Object url, Map<?, ?> headers, Object body) {
        String u = String.valueOf(url);
        checkEgress(u);
        WebClient.RequestBodySpec spec = webClient.post().uri(u)
                .contentType(MediaType.APPLICATION_JSON);
        applyHeaders(spec, headers);
        return exchange(spec.bodyValue(body instanceof String s ? s : JSON.toJSONString(body)));
    }

    private Resp exchange(WebClient.RequestHeadersSpec<?> spec) {
        return spec.exchangeToMono(resp -> resp.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(raw -> new Resp(resp.statusCode().value(), parse(raw),
                                resp.statusCode().is2xxSuccessful())))
                .block(timeout);
    }

    private void checkEgress(String url) {
        String host = URI.create(url).getHost();
        boolean allowed = host != null && egressAllowlist.stream()
                .anyMatch(prefix -> url.startsWith(prefix) || host.equals(URI.create(prefix).getHost()));
        if (!allowed) {
            throw new SecurityException("出网白名单拦截: " + host + " (worker.egress-allowlist)");
        }
    }

    private static void applyHeaders(WebClient.RequestHeadersSpec<?> spec,
                                     Map<?, ?> headers) {
        if (headers != null) {
            // Groovy GString 键值统一 String 化 (脚本侧 "Bearer ${key}" 是 GString)
            headers.forEach((k, v) -> spec.header(String.valueOf(k), String.valueOf(v)));
        }
    }

    private static Object parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.parse(raw);
        } catch (Exception e) {
            return raw;
        }
    }
}
