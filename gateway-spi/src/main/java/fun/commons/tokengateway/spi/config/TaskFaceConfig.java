package fun.commons.tokengateway.spi.config;

import lombok.Data;

import java.time.Duration;
import java.util.List;

/**
 * 任务面参数 (token-gateway.task, face=task/all 时生效; 默认值 = THMP 移植口径, 设计方案 §5.1).
 */
@Data
public class TaskFaceConfig {

    /** 任务过期窗口 (超时 EXPIRED + 全额退款). */
    private Duration expireScan = Duration.ofHours(24);

    /** 资源代理缓存目录 (face=task 实例挂盘). */
    private String resourceCacheDir = "/data/tgw-cache";

    /** 资源代理 sig 签名密钥 (环境变量注入). */
    private String resourceSignKey;

    /** notify 重发退避档位. */
    private List<Duration> notifyRetry = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(10), Duration.ofHours(1));
}
