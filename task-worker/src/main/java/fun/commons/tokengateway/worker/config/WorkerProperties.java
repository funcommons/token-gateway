package fun.commons.tokengateway.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Worker 运行参数 (worker.*).
 */
@Data
@ConfigurationProperties(prefix = "worker")
public class WorkerProperties {

    /** Worker 实例 ID (fencing/审计用; 默认启动生成 wkr-<host>-<uuid8>). */
    private String id;

    /** 拉单间隔. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** 上游轮询间隔 (脚本 poll 钩子的循环节拍; 脚本可用 ctx.config 覆盖). */
    private Duration upstreamPollInterval = Duration.ofSeconds(5);

    /** 脚本目录 (默认仓内 scripts/; 目录下按 <taskType>/<name>.groovy 组织). */
    private String scriptsDir = "scripts";

    /**
     * 脚本源模式 (issue #9): local = 扫描本地目录 (默认, 行为与现状 100% 一致);
     * remote = 拉单前经 HTTP 回调控制层索取 (脚本真源在 MMagiX 侧, mmxp_model_script).
     */
    private ScriptSource scriptSource = ScriptSource.LOCAL;

    /** remote 源参数 (script-source=remote 时生效). */
    private ScriptRemote scriptRemote = new ScriptRemote();

    /** 脚本源模式. */
    public enum ScriptSource {
        /** 本地目录扫描 (scripts/<taskType>/<name>.groovy, 版本序取最新). */
        LOCAL,
        /** 控制层回调索取 (GET {base-url}/{taskType} → {version, hooks}), 成功落本地副本. */
        REMOTE
    }

    /**
     * remote 脚本源参数 (worker.script-remote.*).
     *
     * <p>契约: {@code GET {base-url}/{taskType}} → 信封 {@code {version, hooks}}
     * (hooks = 单文件 Groovy 脚本文本, 含 create/poll/resultMapping 三钩子);
     * 版本号与缓存一致不重复落盘; remote 不可达降级用本地最后副本 (WARN).
     */
    @Data
    public static class ScriptRemote {

        /** 控制层脚本回调端点前缀 (如 http://mmagix:9400/api/v1/internal/work-scripts). */
        private String baseUrl;

        /** 回调鉴权 X-Internal-Token 头 (env 注入禁入仓; 空 = 不发, 仅 localhost). */
        private String internalToken;

        /** 拉取的 taskType 集合 (remote 模式必填——无列表端点, 由部署方显式声明). */
        private List<String> taskTypes = List.of();

        /** 版本比对节流: 窗口内跳过 HTTP, 过期后拉取比对版本 (不变不落盘). */
        private Duration cacheTtl = Duration.ofSeconds(60);
    }

    /** 单 Worker 并发执行任务上限. */
    private int concurrency = 8;

    /** 每 N 次上游轮询检查一次取消信号 (status 端点). */
    private int statusCheckEvery = 3;

    /** 脚本单钩子执行超时硬上限. */
    private Duration hookTimeout = Duration.ofSeconds(60);

    /** 出网白名单 (脚本 http binding 只允许这些 host 前缀; 空 = 全禁, fail-closed). */
    private List<String> egressAllowlist = List.of();

    /** 单任务最大执行时长 (超过即上报 FAILED, 与网关超时钟对齐兜底). */
    private Duration maxTaskDuration = Duration.ofHours(3);
}
