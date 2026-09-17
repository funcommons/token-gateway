package fun.commons.tokengateway.task.controller;

import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskTaskClient;
import fun.commons.tokengateway.task.lotask.LotaskTaskView;
import fun.commons.tokengateway.task.resource.ResourceSigner;
import fun.commons.tokengateway.task.state.TaskMetaStore;
import fun.commons.tokengateway.task.state.TaskNoMappingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 资源代理 (契约 = 《04》yaml: GET /v1/resources/{task_no}/{index}?exp=&sig=).
 *
 * <p>免凭证 (exp+sig 即能力凭证, 浏览器/下载端直取); 验签失败/过期 → 400+10100;
 * 任务非 SUCCEEDED → 409+10402. 流式回源 + 本地缓存盘 (write-through:
 * 缓存命中直接发文件; 未命中拉上游写盘后从盘发, 大文件不驻内存).
 * 上游原始 URL 永不透传 (《05》§4).
 *
 * <p>Web 可看性 (2026-09-17 计费合规测试实测反馈):
 * <ul>
 *   <li>路径支持可选扩展名后缀 {@code /{index}.png} — 剥离后验签, 与无后缀 URL 同 sig 通用;
 *       无后缀旧 URL 永远有效</li>
 *   <li>Content-Type 三层解析: ① 回源时捕获上游响应头 (缺失回退上游 URL 扩展名, 再回退
 *       data: URI 自带 mediatype), ② 旧缓存无 sidecar 时魔数嗅探 (PNG/JPEG/GIF/WEBP/MP4/PDF),
 *       ③ octet-stream 兜底 — 解析结果持久化 {@code {index}.ct} sidecar, 命中缓存免回源直读</li>
 *   <li>Content-Disposition: inline + 文件名带扩展名 — 浏览器内联渲染而非触发下载</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ResourceProxyController {

    private final ResourceSigner resourceSigner;
    private final TaskNoMappingStore mappingStore;
    private final TaskMetaStore metaStore;
    private final LotaskTaskClient lotaskClient;
    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;

    @GetMapping("/v1/resources/{taskNo}/{file}")
    public Mono<ResponseEntity<Flux<DataBuffer>>> fetch(
            @PathVariable String taskNo,
            @PathVariable String file,
            @RequestParam long exp,
            @RequestParam String sig) {
        int index = parseIndex(file);
        if (index < 0) {
            return Mono.error(new RelayException(404, ApiCode.NOT_FOUND.getCode(),
                    "资源索引非法: " + file));
        }
        if (!resourceSigner.verify(taskNo, index, exp, sig)) {
            return Mono.error(new RelayException(400, ApiCode.PARAM_ERROR.getCode(),
                    "资源签名无效或已过期"));
        }
        Path cacheFile = cacheFile(taskNo, index);
        // 命中判定要求非空: write-through 半途失败会留 0 字节文件, 视为未命中走回源 (防空文件毒化)
        if (Files.exists(cacheFile) && fileSizeOrZero(cacheFile) > 0) {
            return Mono.just(serveCacheFile(taskNo, index, cacheFile));
        }
        // 取上游原始 URL: 终态条目里已是代理 URL, 须回 lotask 结果拿原文;
        // 回源凭证 = create 时存入 meta 的渠道 apiKey (上游按渠道验钥)
        return mappingStore.get(taskNo)
                .switchIfEmpty(Mono.error(new RelayException(404, ApiCode.NOT_FOUND.getCode(),
                        "任务不存在: " + taskNo)))
                .flatMap(lotaskId -> metaStore.getMeta(taskNo)
                        .map(meta -> lotaskClient.get(lotaskId)
                                .map(view -> new Object[]{view, meta == null ? null : meta.upstreamApiKey()}))
                        .defaultIfEmpty(lotaskClient.get(lotaskId).map(view -> new Object[]{view, null}))
                        .flatMap(mono -> mono))
                .flatMap(arr -> {
                    LotaskTaskView view = (LotaskTaskView) arr[0];
                    String upstreamKey = (String) arr[1];
                    if (!"SUCCESS".equals(view.status())) {
                        return Mono.error(new RelayException(409, ApiCode.STATE_CONFLICT.getCode(),
                                "任务非 SUCCEEDED, 资源不可取"));
                    }
                    String upstreamUrl = resourceAt(view.result(), index);
                    if (upstreamUrl == null) {
                        return Mono.error(new RelayException(404, ApiCode.NOT_FOUND.getCode(),
                                "资源索引越界: " + index));
                    }
                    return fetchAndCache(taskNo, index, upstreamUrl, cacheFile, upstreamKey);
                });
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> fetchAndCache(String taskNo, int index,
                                                                 String upstreamUrl, Path cacheFile,
                                                                 String upstreamApiKey) {
        try {
            Files.createDirectories(cacheFile.getParent());
        } catch (Exception e) {
            return Mono.error(new RelayException(500, ApiCode.SYSTEM_BUSY.getCode(), "缓存盘不可用"));
        }
        // data: URI (上游同步 API 经 url 字段直回内联 base64, OpenAI 兼容面实测形态):
        // 无源可回 — 直解码写缓存盘, 不走 WebClient (否则 URI 无 host 炸 "Host is not specified")
        if (upstreamUrl.startsWith("data:")) {
            return decodeDataUri(upstreamUrl)
                    .flatMap(bytes -> Mono.fromCallable(() -> {
                        Files.write(cacheFile, bytes);
                        MediaType type = dataUriMediaType(upstreamUrl);
                        writeSidecar(cacheFile, type);
                        return serveCacheFile(taskNo, index, cacheFile);
                    }));
        }
        // issue #16: 预编码签名 URL 须走 URI 重载, uri(String) 模板模式会重复编码 %2B 等序列致上游 403
        WebClient.RequestHeadersSpec<?> spec = webClientBuilder.build().get()
                .uri(java.net.URI.create(upstreamUrl));
        if (upstreamApiKey != null && !upstreamApiKey.isBlank()) {
            spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + upstreamApiKey);
        }
        return spec.retrieve().toEntityFlux(DataBuffer.class)
                .flatMap(entity -> DataBufferUtils.write(entity.getBody(), cacheFile)
                        .then(Mono.fromCallable(() -> {
                            MediaType type = resolveUpstreamMediaType(upstreamUrl,
                                    entity.getHeaders().getContentType());
                            writeSidecar(cacheFile, type);
                            return serveCacheFile(taskNo, index, cacheFile);
                        })))
                .onErrorResume(e -> {
                    log.error("[ResourceProxy] 回源失败: err={}", e.getMessage());
                    return Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                            "上游资源回源失败"));
                });
    }

    /**
     * data:[&lt;mediatype&gt;][;base64],&lt;data&gt; 解码; 仅接受 base64 形态, 非法即 502 口径拒绝.
     */
    private Mono<byte[]> decodeDataUri(String uri) {
        int comma = uri.indexOf(',');
        String metaPart = comma > 5 ? uri.substring(5, comma) : "";
        if (comma < 0 || !metaPart.contains(";base64")) {
            return Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                    "上游资源为不支持的 data URI 形态 (仅支持 base64)"));
        }
        try {
            return Mono.just(java.util.Base64.getDecoder().decode(uri.substring(comma + 1)));
        } catch (IllegalArgumentException e) {
            return Mono.error(new RelayException(502, ApiCode.THIRD_PARTY_ERROR.getCode(),
                    "上游资源 data URI base64 非法"));
        }
    }

    /**
     * 缓存盘直发: Content-Type 读 sidecar (本次部署写入); 旧缓存无 sidecar 时魔数嗅探兜底;
     * 均未命中 → octet-stream. Content-Disposition 恒 inline (浏览器内联渲染, 不触发下载).
     */
    private ResponseEntity<Flux<DataBuffer>> serveCacheFile(String taskNo, int index, Path cacheFile) {
        return serveWithMediaType(taskNo, index, cacheFile, readSidecar(cacheFile));
    }

    private ResponseEntity<Flux<DataBuffer>> serveWithMediaType(String taskNo, int index,
                                                                Path cacheFile, MediaType type) {
        MediaType effective = type != null ? type : sniffMediaType(cacheFile);
        String ext = extensionOf(effective);
        return ResponseEntity.ok()
                .contentType(effective)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + taskNo + "-" + index + "." + ext + "\"")
                .body(DataBufferUtils.readByteChannel(
                        () -> Files.newByteChannel(cacheFile),
                        new DefaultDataBufferFactory(),
                        8192));
    }

    // ---------- Content-Type 解析 ----------

    /** 回源定型: 上游响应头优先 (octet-stream 视为未声明), 回退上游 URL 扩展名, 再回退 octet-stream. */
    static MediaType resolveUpstreamMediaType(String upstreamUrl, MediaType upstreamType) {
        if (upstreamType != null && !MediaType.APPLICATION_OCTET_STREAM.equalsTypeAndSubtype(upstreamType)
                && !"application/unknown".equalsIgnoreCase(upstreamType.toString())) {
            return upstreamType;
        }
        String path = upstreamUrl;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        return MediaTypeFactory.getMediaType(path).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    /** data: URI 自带 mediatype (meta 形如 "image/png;base64"). */
    private static MediaType dataUriMediaType(String uri) {
        int comma = uri.indexOf(',');
        if (comma > 5) {
            String meta = uri.substring(5, comma);
            int semi = meta.indexOf(';');
            String type = semi > 0 ? meta.substring(0, semi) : meta;
            try {
                return MediaType.parseMediaType(type);
            } catch (Exception ignored) {
                // 落到嗅探/兜底
            }
        }
        return null;
    }

    /** 魔数嗅探 (旧缓存 sidecar 缺失兜底): 仅覆盖高频类型, 未识别返回 null (调用方兜底 octet-stream). */
    static MediaType sniffMediaType(Path file) {
        try (var in = Files.newInputStream(file)) {
            byte[] b = in.readNBytes(12);
            if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                return MediaType.IMAGE_PNG;
            }
            if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) {
                return MediaType.IMAGE_JPEG;
            }
            if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
                return MediaType.IMAGE_GIF;
            }
            if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                return MediaType.parseMediaType("image/webp");
            }
            if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
                return MediaType.parseMediaType("video/mp4");
            }
            if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
                return MediaType.APPLICATION_PDF;
            }
        } catch (Exception ignored) {
            // 读不到即 octet-stream
        }
        return null;
    }

    private void writeSidecar(Path cacheFile, MediaType type) {
        if (type == null) {
            return;
        }
        try {
            Files.writeString(sidecar(cacheFile), type.toString());
        } catch (Exception e) {
            log.warn("[ResourceProxy] sidecar 写入失败 (不影响取图): {}", e.getMessage());
        }
    }

    private MediaType readSidecar(Path cacheFile) {
        try {
            Path s = sidecar(cacheFile);
            if (Files.exists(s)) {
                return MediaType.parseMediaType(Files.readString(s).trim());
            }
        } catch (Exception ignored) {
            // sidecar 缺失/损坏 → 嗅探/兜底
        }
        return null;
    }

    private Path sidecar(Path cacheFile) {
        return cacheFile.resolveSibling(cacheFile.getFileName() + ".ct");
    }

    /** MediaType → 文件扩展名 (filename 用; 未知类型回 "bin"). */
    static String extensionOf(MediaType type) {
        if (type == null) {
            return "bin";
        }
        String s = type.getSubtype();
        if ("jpeg".equalsIgnoreCase(s)) {
            return "jpg";
        }
        if ("svg+xml".equalsIgnoreCase(s)) {
            return "svg";
        }
        return switch (s.toLowerCase()) {
            case "png", "jpg", "gif", "webp", "pdf", "mp4", "webm", "mp3", "wav", "html" -> s;
            case "plain" -> "txt";
            default -> "bin";
        };
    }

    /** 路径段解析 "{index}[.{ext}]": index 非法/负数 → -1; 扩展名可有可无 (剥离后验签, 与旧 URL 同 sig). */
    static int parseIndex(String file) {
        if (file == null) {
            return -1;
        }
        String f = file.trim();
        int dot = f.lastIndexOf('.');
        if (dot > 0) {
            String ext = f.substring(dot + 1);
            if (!ext.isEmpty() && ext.chars().allMatch(Character::isLetterOrDigit)) {
                f = f.substring(0, dot);
            }
        }
        try {
            int idx = Integer.parseInt(f);
            return idx >= 0 ? idx : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static String resourceAt(Map<String, Object> result, int index) {
        if (result == null || !(result.get("resources") instanceof List<?> list)
                || index < 0 || index >= list.size()) {
            return null;
        }
        Object v = list.get(index);
        return v instanceof String s ? s : null;
    }

    private long fileSizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0;
        }
    }

    private Path cacheFile(String taskNo, int index) {
        // taskNo 格式 T+时间戳+随机字母数字 (TaskRelayOrchestrator 生成), 无路径穿越面
        return Path.of(props.getTask().getResourceCacheDir(), taskNo, String.valueOf(index));
    }
}
