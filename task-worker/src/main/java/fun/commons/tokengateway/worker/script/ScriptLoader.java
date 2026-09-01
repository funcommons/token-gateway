package fun.commons.tokengateway.worker.script;

import fun.commons.tokengateway.worker.config.WorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 脚本加载器 (《05》§9.3: 真源在 token-gateway 仓 scripts/<taskType>/<name>.groovy,
 * 随 Worker 部署包加载; 按文件名版本序取最新, mtime 变更即换缓存键自动失效).
 *
 * <p>目录约定: scripts/video/kling-v1.groovy → taskType=video;
 * 同类型多脚本时取文件名自然序最大者 (v2 > v1); 灰度用独立 taskType (video-canary).
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

    private final WorkerProperties props;
    private final Map<String, ScriptAsset> byType = new ConcurrentHashMap<>();

    public ScriptLoader(WorkerProperties props) {
        this.props = props;
    }

    /** 启动/定时刷新: 扫描脚本目录重建索引 (mtime 变即新版本). */
    public synchronized void reload() {
        Path root = Path.of(props.getScriptsDir());
        if (!Files.isDirectory(root)) {
            log.warn("[ScriptLoader] 脚本目录不存在: {}", root.toAbsolutePath());
            return;
        }
        try (Stream<Path> walk = Files.walk(root, 2)) {
            List<ScriptAsset> found = walk
                    .filter(p -> p.toString().endsWith(".groovy"))
                    .map(this::load)
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

    /** 当前生效的 taskType 集合 (WorkerLoop 按它拉单). */
    public java.util.Set<String> taskTypes() {
        return byType.keySet();
    }

    public Optional<ScriptAsset> forType(String taskType) {
        return Optional.ofNullable(byType.get(taskType));
    }

    private ScriptAsset load(Path path) {
        try {
            String taskType = path.getParent().getFileName().toString();
            return new ScriptAsset(taskType, path.toString(),
                    Files.getLastModifiedTime(path).toMillis(), Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
