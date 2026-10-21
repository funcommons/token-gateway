package fun.commons.tokengateway.spi.config;

import lombok.Data;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 任务面参数 (token-gateway.task, face=task/all 时生效; 《05_任务面lotask4j托管方案》V1.1).
 */
@Data
public class TaskFaceConfig {

    /** 任务过期窗口 (超时 EXPIRED + 全额退款; 超时钟默认 deadline, 可按 task_type 覆盖). */
    private Duration expireScan = Duration.ofHours(24);

    /**
     * 任务面渠道路由端点 (work 域分发, #12): face=task/all 的 distribute 走此路径.
     *
     * <p>与 LLM 面的 chat 端点 {@code token-gateway.route.distribute-path} 分离 —
     * 回归 2026-09-21-04 BL11 P1-5: 单键共用曾使 LLM face=all 部署的本地渠道路由
     * 误打 work-channels 端点 (宿主按 workId 解析, LLM 形状请求无 workId →
     * {@code Long.parseLong(null)} → 502/10004).
     */
    private String distributePath = "/api/v1/internal/work-channels/distribute";

    /** 资源代理缓存目录 (仅缓存模式使用; 显式 resourceCacheEnabled=true 时须挂持久卷并显式配置). */
    private String resourceCacheDir = "./data/tgw-cache";

    /**
     * 资源代理缓存盘开关 (缺省 false = 直通模式).
     * 直通: 回源上游流式转发, 全程不落盘不依赖本地目录 — 无本地状态, 多副本 /
     * 只读根文件系统 (allinone appliance) 开箱即用 (2026-10-21 缺省反转, 根因:
     * 容器内 createDirectories 失败 → 「缓存盘不可用」500 → MMagiX2 内链转存全断).
     * true = 写穿本地盘 (legacy): 命中直发, 未命中拉上游写盘后从盘发 —
     * 价值仅剩「上游签名 URL 过期后盘上续命」, 需自行保证目录可写.
     */
    private boolean resourceCacheEnabled = false;

    /** 资源代理 sig 签名密钥 (环境变量注入). */
    private String resourceSignKey;

    /** notify 重发退避档位. */
    private List<Duration> notifyRetry = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1));

    /** notify 回调 X-THMP-Signature 签名密钥 (环境变量注入; 调用方以此验签). */
    private String notifySignKey;

    /** lotask4j 平台对接 (任务状态托管方, 零改造接入). */
    private LotaskFaceConfig lotask = new LotaskFaceConfig();

    /**
     * 任务计费面 (issue #31): 任务三端点独立寻址, 与 LLM 面计费契约互斥
     * (全额 amount 直传 vs token 估价重算). 逐字段缺省回退 {@code token-gateway.billing.*}.
     */
    private TaskBillingFaceConfig billing = new TaskBillingFaceConfig();

    /** 路由快照 AES-GCM 加密密钥 (环境变量注入; 仅网关与自写 Worker 持有, 平台只见密文). */
    private String snapshotCipherKey;

    /** 超时钟: 按 task_type 覆盖默认 expireScan (如 video=2h, image=30m). */
    private Map<String, Duration> timeouts = Map.of();

    /**
     * lotask submit 的 task_type 粒度 (issue #13): modality (默认, 现状) | model.
     * <p>remote Worker 脚本以 modelCode 索引 (worker.script-remote.task-types) 时须配 model,
     * 否则任务入队后按模态建单、Worker 按模型编码拉单, 无人认领永 PENDING.
     * 仅影响 lotask 侧 task_type 及同键的 timeouts/TaskMeta; 网关 API 面
     * (poll_url /v1/{modality}s/{taskNo}) 不变.
     */
    private String submitTaskType = "modality";

    /** 解析 task_type 的超时窗口 (无覆盖时取 expireScan). */
    public Duration timeoutOf(String taskType) {
        Duration override = taskType == null ? null : timeouts.get(taskType);
        return override != null ? override : expireScan;
    }

    /** 解析本次 submit 的 task_type: submit-task-type=model 时取 body.model, 非法值/缺 model 回退模态. */
    public String submitTaskTypeOf(String modality, String model) {
        if ("model".equalsIgnoreCase(submitTaskType)
                && model != null && !model.isBlank()) {
            return model;
        }
        return modality;
    }

    /** work 域分发端点 (yml 显式置空时回退默认, 与 {@link RouteFaceConfig#getDistributePath} 同口径). */
    public String getDistributePath() {
        return distributePath == null || distributePath.isBlank()
                ? "/api/v1/internal/work-channels/distribute" : distributePath;
    }

    public void setDistributePath(String distributePath) {
        this.distributePath = distributePath;
    }
}
