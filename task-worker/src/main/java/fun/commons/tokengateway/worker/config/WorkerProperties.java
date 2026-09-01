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
