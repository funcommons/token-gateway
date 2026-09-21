package fun.commons.tokengateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端错误契约配置 (issue #24): LLM 面错误形状切换 + 能力面业务码透传白名单.
 *
 * <pre>
 * gateway:
 *   # LLM 面 (/v1/chat/completions, /v1/messages 前缀) 错误响应形状:
 *   #   envelope (默认) = 网关 6 字段信封 (文档化既定契约)
 *   #   openai          = OpenAI {"error":{...}} 形状 (OpenAI SDK 直接可解析)
 *   # 切换前与接入方对齐契约 — 信封是既定公开契约, 未对齐即切换对存量接入方是 breaking;
 *   # 任务面 / 内部端点恒为信封, 开关不误伤。
 *   error-shape: envelope
 *   # 能力面业务码透传白名单 (原码 → 透传 HTTP 状态): RelayOrchestrator distribute/preConsume
 *   # 失败分支原码命中 → RelayException(透传状态, 原码, 原始 message); 未命中走既有映射
 *   # (10400·20103→404 / 10617→402 / 其余 502+10004)。白名单之外的原码不进网关公开错误码
 *   # 空间 (仅 message 文本残存)。配置与默认白名单按键合并, 同键覆盖。
 *   error-passthrough-codes:
 *     4090: 403    # 风控拒绝
 *     10601: 402   # 余额不足 (能力面口径)
 *     10602: 404   # model not found (能力面口径)
 *     10603: 404
 *     10402: 409   # 状态冲突
 *     10612: 403   # IP 白名单拒绝 (issue #37 起入默认, 与 4090 同类 = Key 级安全拒绝)
 * </pre>
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "gateway")
public class ErrorContractProperties {

    /** LLM 面错误响应形状 (issue #24): envelope = 6 字段信封, openai = OpenAI error 形状. */
    public enum ErrorShape {
        /** 网关 6 字段信封 (默认, 文档化既定契约). */
        ENVELOPE,
        /** OpenAI {"error":{message,type,param,code}} 形状 + 顶层 trace_id. */
        OPENAI
    }

    /** LLM 面错误形状, 默认 envelope (现契约, 默认关是硬约束). */
    private ErrorShape errorShape = ErrorShape.ENVELOPE;

    /**
     * 能力面业务码透传白名单 (原码 → 透传 HTTP 状态), 默认按 issue #24 语义.
     * <p>注意: yml 配置按键合并进此 Map (同键覆盖, 不可经配置删除默认键);
     * 需要收窄白名单时只能覆盖同键值或以代码装配替换。
     */
    private Map<Integer, Integer> errorPassthroughCodes = defaultPassthroughCodes();

    /** 是否 openai 错误形状 (LLM 面 path 命中时生效, 见 OpenAiErrorAssembler#isLlmFacePath). */
    public boolean isOpenAiShape() {
        return errorShape == ErrorShape.OPENAI;
    }

    /**
     * 查透传 HTTP 状态: 能力面原码命中白名单 → 返回透传状态; 未命中/null → 返回 null
     * (调用方走既有默认映射).
     */
    public Integer passthroughStatusOf(Integer capabilityCode) {
        if (capabilityCode == null) {
            return null;
        }
        return errorPassthroughCodes.get(capabilityCode);
    }

    /**
     * issue #24 默认白名单 (4090→403, 10601→402, 10602→404, 10603→404, 10402→409);
     * 首次扩员: 10612→403 自 issue #37 起入默认 (IP 白名单拒绝, 与 4090 同类 = Key 级安全拒绝)。
     */
    public static Map<Integer, Integer> defaultPassthroughCodes() {
        Map<Integer, Integer> defaults = new LinkedHashMap<>();
        defaults.put(4090, 403);
        defaults.put(10601, 402);
        defaults.put(10602, 404);
        defaults.put(10603, 404);
        defaults.put(10402, 409);
        defaults.put(10612, 403);
        return defaults;
    }
}
