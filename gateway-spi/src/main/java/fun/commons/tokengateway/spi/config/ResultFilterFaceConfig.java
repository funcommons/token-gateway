package fun.commons.tokengateway.spi.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Duration;

/**
 * 终态结果过滤面配置 (token-gateway.result-filter, issue #43, 第 9 面).
 *
 * <p>任务面 SUCCEEDED 终态 result (含上游渠道裸 URL) 落库前, 网关回调<b>消费方</b>
 * (能力面, 如 mmagix) 改写 result —— 消费方下载产物转存自家 OSS (30 天保留)/审核/脱敏/
 * 日志/缓存; 网关存储与对外只见改写后值. 只拦 SUCCEEDED, FAILED/EXPIRED/CANCELLED 不过滤.
 *
 * <p><b>开关默认关</b> ({@code enabled=false}): 关时终态链零新逻辑 (回归红线).
 * 逐字段缺省回退既有模式 (url 回退 {@code gateway.backend.url}, 凭证回退 backend
 * internal-token 平移语义), timeout 缺省 {@link #DEFAULT_TIMEOUT} (消费方下载+上传大产物
 * 需要, 不从 backend.timeout 回退), path 缺省 {@link #DEFAULT_PATH}.
 *
 * <p>失败语义: 改写 RPC 失败/超时/信封 code≠0 → 不落终态条目 (任务维持非终态, 对账/超时钟
 * 重放终态事件自然重试); 尝试计数 Redis {@code tgw:result-filter:attempts:{taskNo}} INCR,
 * 超过 {@link #maxAttempts} → {@code [ResultFilter-DeadLetter]} 死信日志 + fail-open 落原值.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ResultFilterFaceConfig extends EndpointConfig {

    /** 端点 path 缺省值 (单端点, 无三件套前缀语义; 键名 path-prefix 与 billing 族面配置风格对齐). */
    public static final String DEFAULT_PATH = "/v1/internal/tasks/result-filter";

    /** 超时缺省 60s (消费方需下载上游产物 + 上传自家 OSS, 大产物耗时; 不回退 backend.timeout). */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /** 开关 (默认关: 终态链一行新逻辑不执行, 存量行为零变化). */
    private boolean enabled = false;

    /**
     * 改写端点路径 (缺省 {@link #DEFAULT_PATH}; 注意继承自 {@link EndpointConfig} 的
     * {@code path} 字段本面不消费, 配 path 无效 —— 与 task.billing 的 path-prefix 同口径).
     */
    private String pathPrefix;

    /**
     * 平移态 internal-token 形态凭证 (auth 未显式配时映射 jwt, 同 gateway.backend 平移语义).
     * 缺省回退 backend internal-token.
     */
    private String internalToken;

    /** 改写失败最大重试次数 (INCR 计数超过即死信 + fail-open 落原值, 不卡死任务). */
    private int maxAttempts = 5;
}
