package fun.commons.tokengateway.task.controller;

import com.alibaba.fastjson2.JSONObject;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

/**
 * 资源代理 (契约 = 《04》yaml: GET /v1/resources/{task_no}/{index}?exp=&sig=).
 *
 * <p>免凭证 (exp+sig 即能力凭证, 浏览器/下载端直取); 验签失败/过期 → 400+10100;
 * 任务非 SUCCEEDED → 409+10402. 流式回源 + 本地缓存盘 (write-through:
 * 缓存命中直接发文件; 未命中拉上游写盘后从盘发, 大文件不驻内存).
 * 上游原始 URL 永不透传 (《05》§4).
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

    @GetMapping("/v1/resources/{taskNo}/{index}")
    public Mono<ResponseEntity<Flux<DataBuffer>>> fetch(
            @PathVariable String taskNo,
            @PathVariable int index,
            @RequestParam long exp,
            @RequestParam String sig) {
        if (!resourceSigner.verify(taskNo, index, exp, sig)) {
            return Mono.error(new RelayException(400, ApiCode.PARAM_ERROR.getCode(),
                    "资源签名无效或已过期"));
        }
        Path cacheFile = cacheFile(taskNo, index);
        // 命中判定要求非空: write-through 半途失败会留 0 字节文件, 视为未命中走回源 (防空文件毒化)
        if (Files.exists(cacheFile) && fileSizeOrZero(cacheFile) > 0) {
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(DataBufferUtils.readByteChannel(
                            () -> Files.newByteChannel(cacheFile),
                            new org.springframework.core.io.buffer.DefaultDataBufferFactory(),
                            8192)));
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
                    return fetchAndCache(upstreamUrl, cacheFile, upstreamKey);
                });
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> fetchAndCache(String upstreamUrl, Path cacheFile,
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
                        return serveCacheFile(cacheFile);
                    }));
        }
        // issue #16: 预编码签名 URL 须走 URI 重载, uri(String) 模板模式会重复编码 %2B 等序列致上游 403
        WebClient.RequestHeadersSpec<?> spec = webClientBuilder.build().get()
                .uri(java.net.URI.create(upstreamUrl));
        if (upstreamApiKey != null && !upstreamApiKey.isBlank()) {
            spec.header(org.springframework.http.HttpHeaders.AUTHORIZATION,
                    "Bearer " + upstreamApiKey);
        }
        Flux<DataBuffer> upstream = spec
                .retrieve()
                .bodyToFlux(DataBuffer.class);
        return DataBufferUtils.write(upstream, cacheFile)
                .then(Mono.fromCallable(() -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(DataBufferUtils.readByteChannel(
                                () -> Files.newByteChannel(cacheFile),
                                new org.springframework.core.io.buffer.DefaultDataBufferFactory(),
                                8192))))
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

    private ResponseEntity<Flux<DataBuffer>> serveCacheFile(Path cacheFile) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(DataBufferUtils.readByteChannel(
                        () -> Files.newByteChannel(cacheFile),
                        new org.springframework.core.io.buffer.DefaultDataBufferFactory(),
                        8192));
    }

    @SuppressWarnings("unchecked")
    private static String resourceAt(java.util.Map<String, Object> result, int index) {
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
