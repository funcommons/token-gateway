package fun.commons.tokengateway.worker.script;

import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.worker.config.WorkerProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 脚本加载器 (《05》§9.3; issue #9 双源).
 *
 * <p><b>local 源</b> (默认, 行为与现状 100% 一致): 扫描 {@code scripts/<taskType>/<name>.groovy},
 * 同类型取文件名自然序最大者 (v2 > v1); mtime 变更即换缓存键自动失效; 灰度用独立 taskType.
 *
 * <p><b>remote 源</b> (MMagiX 转接形态, 脚本真源在控制层 mmxp_model_script):
 * <ul>
 *   <li>拉单前 {@code GET {base-url}/{taskType}} → 信封 {@code {version, hooks}};
 *       版本号与缓存一致则不落盘 (cacheTtl 窗口内连 HTTP 都不发)</li>
 *   <li>成功后落本地副本 {@code <scriptsDir>/.cache/<taskType>/v-<version>.groovy}
 *       (原子写: tmp → move); remote 不可达时降级用本地最后副本 + WARN ——
 *       控制层故障时 Worker 自举, 已在跑的 taskType 不中断</li>
 *   <li>taskType 发现: 配置声明 ({@code worker.script-remote.task-types}) ∪ 磁盘缓存
 *       (首启自举) ∪ 已加载索引</li>
 *   <li>fail-fast: remote 模式 base-url / task-types 缺失即抛 IllegalStateException
 *       (误配裸地址跑成空脚本比报错更糟)</li>
 * </ul>
 *
 * <p><b>任务内版本锁定</b>: WorkerLoop.runTask 在任务开始时取得 {@link ScriptAsset}
 * (record, 不可变) 引用后全程持有——reload 热载只换本类索引, 不影响运行中任务;
 * create 与 poll/resultMapping 恒同版本。跨 Worker 重启重领的任务整体重跑 create
 * (lotask 既有语义, 脚本需对 re-create 幂等, 如 token-mock poll 404 不判死).
 */
@Slf4j
@Component
public class ScriptLoader {

    /** 已加载脚本 (路径 + mtime + 文本). */
    public record ScriptAsset(String taskType, String path, long mtime, String source) {
        public String cacheKey() {
            return path + "@" + mtime;
        }
    }

    private static final ParameterizedTypeReference<ApiResponse<RemoteScript>> REMOTE_TYPE =
            new ParameterizedTypeReference<>() {};

    /** remote 响应载荷 (信封 data). */
    @Data
    static class RemoteScript {
        private String version;
        private String hooks;
    }

    private final WorkerProperties props;
    private final WebClient.Builder webClientBuilder;

    /** 当前生效索引 (local 扫描 / remote 拉取殊途同归). */
    private final Map<String, ScriptAsset> byType = new ConcurrentHashMap<>();

    /** remote 源: taskType → 已缓存版本号 (版本比对不重复落盘). */
    private final Map<String, String> remoteVersions = new ConcurrentHashMap<>();

    /** remote 源: taskType → 上次成功拉取时刻 (cacheTtl 节流). */
    private final Map<String, Long> remoteFetchedAt = new ConcurrentHashMap<>();

    public ScriptLoader(WorkerProperties props) {
        this(props, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ScriptLoader(WorkerProperties props, WebClient.Builder webClientBuilder) {
        this.props = props;
        this.webClientBuilder = webClientBuilder;
    }

    /** 启动/定时刷新: 按 script-source 分派 (local 扫描 / remote 拉取). */
    public synchronized void reload() {
        if (props.getScriptSource() == WorkerProperties.ScriptSource.REMOTE) {
            reloadRemote();
        } else {
            reloadLocal();
        }
    }

    /** 当前生效的 taskType 集合 (WorkerLoop 按它拉单). */
    public java.util.Set<String> taskTypes() {
        return byType.keySet();
    }

    public Optional<ScriptAsset> forType(String taskType) {
        return Optional.ofNullable(byType.get(taskType));
    }

    // ---------------------------------------------------------------- local

    private void reloadLocal() {
        Path root = Path.of(props.getScriptsDir());
        if (!Files.isDirectory(root)) {
            log.warn("[ScriptLoader] 脚本目录不存在: {}", root.toAbsolutePath());
            return;
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            List<ScriptAsset> found = walk
                    .filter(p -> p.toString().endsWith(".groovy"))
                    .filter(p -> !isCachePath(p))          // .cache 仅 remote 副本, local 不索引
                    .map(this::loadFromDisk)
                    .toList();
            byType.clear();
            for (ScriptAsset asset : found) {
                byType.merge(asset.taskType(), asset,
                        (a, b) -> a.path().compareTo(b.path()) >= 0 ? a : b);
            }
            log.info("[ScriptLoader] 加载脚本 {} 个, 覆盖 taskType: {}", found.size(),
                    byType.keySet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ScriptAsset loadFromDisk(Path path) {
        try {
            String taskType = path.getParent().getFileName().toString();
            return new ScriptAsset(taskType, path.toString(),
                    Files.getLastModifiedTime(path).toMillis(), Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --------------------------------------------------------------- remote

    private void reloadRemote() {
        WorkerProperties.ScriptRemote cfg = props.getScriptRemote();
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "worker.script-remote.base-url 缺失 (script-source=remote 必填, fail-fast)");
        }
        if (cfg.getTaskTypes().isEmpty() && byType.isEmpty()) {
            throw new IllegalStateException(
                    "worker.script-remote.task-types 为空 (remote 无列表端点, 须显式声明)");
        }
        for (String taskType : remoteCandidates(cfg)) {
            Long fetchedAt = remoteFetchedAt.get(taskType);
            if (fetchedAt != null
                    && System.currentTimeMillis() - fetchedAt < cfg.getCacheTtl().toMillis()) {
                continue; // ttl 窗口内跳过 (版本不变不重复拉)
            }
            fetchRemote(taskType, cfg);
        }
    }

    /** taskType 候选: 配置声明 ∪ 磁盘缓存自举 ∪ 当前索引. */
    private Set<String> remoteCandidates(WorkerProperties.ScriptRemote cfg) {
        Set<String> candidates = new LinkedHashSet<>(cfg.getTaskTypes());
        candidates.addAll(byType.keySet());
        if (byType.isEmpty()) { // 首启自举: 控制层不可达时从磁盘缓存恢复
            scanCacheDir().forEach(asset -> {
                byType.putIfAbsent(asset.taskType(), asset);
                candidates.add(asset.taskType());
            });
        }
        return candidates;
    }

    /** 磁盘缓存扫描 (.cache/<taskType>/v-*.groovy, 同类型取文件名序最大). */
    private List<ScriptAsset> scanCacheDir() {
        Path cacheRoot = cacheRoot();
        if (!Files.isDirectory(cacheRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(cacheRoot, 2)) {
            return walk.filter(p -> p.toString().endsWith(".groovy"))
                    .map(this::loadFromDisk)
                    .toList();
        } catch (IOException e) {
            log.warn("[ScriptLoader/remote] 缓存目录扫描失败: {}", e.getMessage());
            return List.of();
        }
    }

    private void fetchRemote(String taskType, WorkerProperties.ScriptRemote cfg) {
        try {
            ApiResponse<RemoteScript> resp = webClientBuilder.build().get()
                    .uri(cfg.getBaseUrl() + "/" + taskType)
                    .headers(h -> {
                        if (cfg.getInternalToken() != null && !cfg.getInternalToken().isBlank()) {
                            h.set("X-Internal-Token", cfg.getInternalToken());
                        }
                    })
                    .retrieve()
                    .bodyToMono(REMOTE_TYPE)
                    .timeout(Duration.ofSeconds(5))
                    .block();
            if (resp == null || !resp.isSuccess() || resp.getData() == null
                    || resp.getData().getVersion() == null
                    || resp.getData().getHooks() == null || resp.getData().getHooks().isBlank()) {
                throw new IllegalStateException("响应无效: code="
                        + (resp == null ? "null" : resp.getCode()));
            }
            RemoteScript script = resp.getData();
            remoteFetchedAt.put(taskType, System.currentTimeMillis());
            String cached = remoteVersions.get(taskType);
            if (script.getVersion().equals(cached)
                    && byType.containsKey(taskType)) {
                return; // 版本未变
            }
            Path file = writeCache(taskType, script.getVersion(), script.getHooks());
            byType.put(taskType, loadFromDisk(file));
            remoteVersions.put(taskType, script.getVersion());
            pruneStaleCache(taskType, file);
            log.info("[ScriptLoader/remote] {} 拉取成功: version={}, cache={}",
                    taskType, script.getVersion(), file);
        } catch (Exception e) {
            remoteFetchedAt.remove(taskType); // 失败不占 ttl 窗口, 下轮重试
            if (byType.containsKey(taskType)) {
                log.warn("[ScriptLoader/remote] {} 拉取失败, 降级用本地最后副本: {}",
                        taskType, e.getMessage());
            } else {
                log.error("[ScriptLoader/remote] {} 拉取失败且无本地副本, 本轮不可用: {}",
                        taskType, e.getMessage());
            }
        }
    }

    /** 原子落盘: .cache/<taskType>/v-<version>.groovy (tmp → move). */
    private Path writeCache(String taskType, String version, String hooks) {
        try {
            Path dir = cacheRoot().resolve(taskType);
            Files.createDirectories(dir);
            Path target = dir.resolve("v-" + sanitize(version) + ".groovy");
            Path tmp = dir.resolve(target.getFileName() + ".tmp");
            Files.writeString(tmp, hooks);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 新版本落盘后清理同类型旧版本副本 (只保留当前). */
    private void pruneStaleCache(String taskType, Path keep) {
        try (Stream<Path> list = Files.list(keep.getParent())) {
            list.filter(p -> !p.equals(keep) && p.toString().endsWith(".groovy"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 清理失败不影响加载
                        }
                    });
        } catch (IOException e) {
            log.debug("[ScriptLoader/remote] 旧缓存清理失败: {}", e.getMessage());
        }
    }

    private Path cacheRoot() {
        return Path.of(props.getScriptsDir(), ".cache");
    }

    private static boolean isCachePath(Path p) {
        return p.getParent() != null && p.getParent().getFileName() != null
                && p.getParent().getFileName().toString().startsWith(".");
    }

    private static String sanitize(String version) {
        return version.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
