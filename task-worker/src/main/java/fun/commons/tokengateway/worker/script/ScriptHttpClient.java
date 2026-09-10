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
        // issue #17: 任务面资源常态为数 MB (gpt-image 系列恒回 b64_json, 图片/音频二进制),
        // WebClient 默认 256KB 缓冲直接抛 ExceededLimitException —— 放宽到 32MB
        this.webClient = builder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
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

    /**
     * multipart 表单提交 (#11): parts 元素两种形态 ——
     * 标量字段 {@code [name: 'model', value: 'gpt-image-2']} 与
     * 文件零件 {@code [name: 'image[]', bytes: byte[], filename: 'ref.png', contentType: 'image/png']}.
     * 响应与 {@link #post} 同构 (JSON 解析); 出网白名单同样约束.
     */
    public Resp postMultipart(Object url, Map<?, ?> headers, List<?> parts) {
        String u = String.valueOf(url);
        checkEgress(u);
        org.springframework.http.client.MultipartBodyBuilder builder =
                new org.springframework.http.client.MultipartBodyBuilder();
        if (parts != null) {
            for (Object partObj : parts) {
                if (!(partObj instanceof Map<?, ?> rawPart)) {
                    continue;
                }
                java.util.Map<String, Object> part = new java.util.HashMap<>();
                rawPart.forEach((k, v) -> part.put(String.valueOf(k), v));
                String name = String.valueOf(part.get("name"));
                if (part.get("bytes") instanceof byte[] bytes) {
                    builder.part(name, new org.springframework.core.io.ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return String.valueOf(part.getOrDefault("filename", "file"));
                        }
                    });
                } else {
                    builder.part(name, String.valueOf(part.get("value")));
                }
            }
        }
        WebClient.RequestHeadersSpec<?> spec = webClient.post().uri(u)
                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                .bodyValue(builder.build());
        applyHeaders(spec, headers);
        return exchange(spec);
    }

    /**
     * 二进制下载 (#11): 参考图下载等场景; 返回原始字节 (不做 JSON 解析), 白名单同样约束.
     */
    public ByteResp getBytes(Object url, Map<?, ?> headers) {
        String u = String.valueOf(url);
        checkEgress(u);
        WebClient.RequestHeadersSpec<?> spec = webClient.get().uri(u);
        applyHeaders(spec, headers);
        return spec.exchangeToMono(resp -> resp.bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(bytes -> new ByteResp(resp.statusCode().value(), bytes,
                                resp.statusCode().is2xxSuccessful())))
                .block(timeout);
    }

    /**
     * 二进制响应视图: {status, bytes, ok}.
     */
    public record ByteResp(int status, byte[] bytes, boolean ok) {
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
        if (headers == null) {
            return;
        }
        // Groovy GString 键值统一 String 化 (脚本侧 "Bearer ${key}" 是 GString)
        headers.forEach((k, v) -> {
            String name = String.valueOf(k);
            String value = String.valueOf(v);
            // issue #15: Content-Type 覆盖默认值而非追加 (spec.header 会拼重复头,
            // 严格上游如 DashScope 以 400 拒收 "application/json,application/json")
            if (spec instanceof WebClient.RequestBodySpec bodySpec
                    && "Content-Type".equalsIgnoreCase(name)) {
                bodySpec.contentType(MediaType.parseMediaType(value));
            } else {
                spec.header(name, value);
            }
        });
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
