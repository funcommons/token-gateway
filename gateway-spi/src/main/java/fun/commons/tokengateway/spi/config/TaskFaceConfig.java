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

    /** 资源代理缓存目录 (face=task 实例挂盘). */
    private String resourceCacheDir = "/data/tgw-cache";

    /** 资源代理 sig 签名密钥 (环境变量注入). */
    private String resourceSignKey;

    /** notify 重发退避档位. */
    private List<Duration> notifyRetry = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1));

    /** notify 回调 X-THMP-Signature 签名密钥 (环境变量注入; 调用方以此验签). */
    private String notifySignKey;

    /** lotask4j 平台对接 (任务状态托管方, 零改造接入). */
    private LotaskFaceConfig lotask = new LotaskFaceConfig();

    /** 路由快照 AES-GCM 加密密钥 (环境变量注入; 仅网关与自写 Worker 持有, 平台只见密文). */
    private String snapshotCipherKey;

    /** 超时钟: 按 task_type 覆盖默认 expireScan (如 video=2h, image=30m). */
    private Map<String, Duration> timeouts = Map.of();

    /** 解析 task_type 的超时窗口 (无覆盖时取 expireScan). */
    public Duration timeoutOf(String taskType) {
        Duration override = taskType == null ? null : timeouts.get(taskType);
        return override != null ? override : expireScan;
    }
}
