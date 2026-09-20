package fun.commons.tokengateway.exception;

import com.fasterxml.jackson.annotation.JsonProperty;
import fun.commons.tokengateway.trace.TraceWebFilter;
import org.springframework.web.server.ServerWebExchange;

import java.util.UUID;

/**
 * OpenAI 错误形状组装器 (issue #24): 把 {@link RelayException} 语义组装为
 * OpenAI {@code {"error":{...}}} 响应体, 供 GlobalExceptionHandler 在
 * {@code gateway.error-shape=openai} 且 LLM 面 path 命中时出口分流使用.
 *
 * <p>线格式样例 (HTTP 401):
 * <pre>
 * {"error":{"message":"令牌无效","type":"authentication_error","param":null,"code":"10202"},
 *  "trace_id":"c0a80101-..."}
 * </pre>
 *
 * <p><b>契约保真决策 (javadoc 即规格)</b> — 信封三要素不丢失:
 * <ul>
 *   <li>信封 code → {@code error.code} (字符串): 恒为网关业务码; 白名单透传场景 =
 *   能力面原码 (如 "4090")。OpenAI 原生语义 code 字符串 (invalid_api_key /
 *   model_not_found / insufficient_quota) <b>不单列</b> — 其语义由 {@code error.type}
 *   承载 (一一对应), OpenAI SDK 以 HTTP 状态 + {@code error.type} 判别错误类别,
 *   {@code error.code} 本就是自由字符串字段, 放网关业务码同时满足 OpenAI SDK 可解析
 *   与网关码表不丢。error.type ↔ OpenAI 原生等价 code 对照见
 *   docs/用户文档/03_通用约定.md「错误形状切换」。</li>
 *   <li>信封 message → {@code error.message} 原样透传, 不加前后缀。</li>
 *   <li>信封 trace_id → 顶层 {@code trace_id} 字段 (选顶层而非 message 尾部: 机器可读、
 *   不污染 message 文案; OpenAI 客户端忽略未知顶层字段, 安全), 取值与响应头
 *   {@code X-Trace-Id} 同源 (TraceWebFilter), 贯通网关/后端/访问日志; 头缺失时兜底生成
 *   UUID (单元测试等无过滤器链场景)。</li>
 *   <li>{@code error.param} 恒 {@code null} (OpenAI 线格式惯例保留该键)。</li>
 * </ul>
 *
 * <p>type 映射 (按 HTTP 状态): 401→authentication_error / 402→insufficient_quota /
 * 403→permission_error / 404→not_found_error / 429→rate_limit_error /
 * 5xx→api_error / 其余 4xx (含 400)→invalid_request_error。
 */
public final class OpenAiErrorAssembler {

    private OpenAiErrorAssembler() {
    }

    /**
     * LLM 面 path 判定 (开关只对 LLM 面生效, 任务面/内部端点维持信封):
     * {@code /v1/chat} 或 {@code /v1/messages} 前缀 (含 /v1/messages/count_tokens).
     */
    public static boolean isLlmFacePath(String path) {
        return path != null && (path.startsWith("/v1/chat") || path.startsWith("/v1/messages"));
    }

    /**
     * HTTP 状态 → OpenAI error.type 映射 (见类 javadoc 映射表).
     */
    public static String typeOf(int httpStatus) {
        if (httpStatus >= 500) {
            return "api_error";
        }
        return switch (httpStatus) {
            case 401 -> "authentication_error";
            case 402 -> "insufficient_quota";
            case 403 -> "permission_error";
            case 404 -> "not_found_error";
            case 429 -> "rate_limit_error";
            default -> "invalid_request_error";
        };
    }

    /**
     * 组装 OpenAI 错误形状响应体 (字段承载决策见类 javadoc).
     *
     * @param httpStatus 语义 HTTP 状态 (决定 error.type)
     * @param code       网关业务码 (白名单透传场景 = 能力面原码), 字符串化放 error.code
     * @param message    原始错误消息, 原样放 error.message
     * @param traceId    链路 ID, 放顶层 trace_id 字段
     */
    public static Body assemble(int httpStatus, int code, String message, String traceId) {
        return new Body(new Payload(message, typeOf(httpStatus), null, String.valueOf(code)), traceId);
    }

    /**
     * 取当前请求链路 ID: 响应头 X-Trace-Id (TraceWebFilter 在过滤器链最早段已写入) 同源;
     * 头缺失 (如单元测试无过滤器链) 兜底生成 UUID.
     */
    public static String currentTraceId(ServerWebExchange exchange) {
        if (exchange != null && exchange.getResponse().getHeaders().getFirst(
                TraceWebFilter.TRACE_ID_HEADER) != null) {
            return exchange.getResponse().getHeaders().getFirst(TraceWebFilter.TRACE_ID_HEADER);
        }
        return UUID.randomUUID().toString();
    }

    /**
     * OpenAI 错误响应体: {@code error} 载荷 + 顶层 {@code trace_id} (契约保真见类 javadoc).
     */
    public record Body(Payload error, @JsonProperty("trace_id") String traceId) {
    }

    /**
     * OpenAI error 载荷: message 原样 / type 按状态映射 / param 恒 null / code = 网关业务码.
     */
    public record Payload(String message, String type, String param, String code) {
    }
}
