package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.exception.RelayException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

/**
 * 上游错误归类与软失败识别 (issue #1, 分类口径对齐 MMagiX gateway UpstreamErrorPolicy).
 *
 * <p>errorCode 归类: 能从异常 (含 cause 链) 取到 HTTP 状态 → {@code HTTP_<status>},
 * 否则 {@code UPSTREAM_ERROR}. 渠道失败上报用真实状态码, 不再恒 502,
 * 运营可区分 401 (key 失效) / 429 (限流) / 400 (参数) / 5xx.
 *
 * <p>软失败: 容错型代理网关故障时常回 {@code 200 + {"error": {...}}} 错误载荷
 * (OpenAI/Anthropic 错误体通式: 顶层 {@code error} 为 Map). 不识别会被当成功结算
 * 并触发 record-success 清零渠道失败计数, 冻结/降级永不触发.
 */
public final class UpstreamErrorPolicy {

    private UpstreamErrorPolicy() {
    }

    /**
     * 上游错误是否值得换道重试: 连接级中断 (PrematureClose, 常见于 SSE 提前断连) /
     * 5xx / 401 (key 失效) / 429 (限流) / 超时 → 可重试;
     * 其余 4xx (参数错误等, 换渠道也必失败) 不重试.
     */
    public static boolean isRetryable(Throwable err) {
        // 第一轮: 连接级中断 (cause 链任意位置, 如 WCRE(200) 包裹的 PrematureClose) → 可重试
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof reactor.netty.http.client.PrematureCloseException) {
                return true;
            }
        }
        // 第二轮: 按 HTTP 状态判定
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException wcre) {
                int status = wcre.getStatusCode().value();
                return status >= 500 || status == 401 || status == 429;
            }
        }
        return true;
    }

    /** 渠道失败上报 errorCode: RelayException / WCRE (含 cause 链) → HTTP_&lt;status&gt;, 其余 → UPSTREAM_ERROR. */
    public static String errorCodeOf(Throwable err) {
        if (err instanceof RelayException re) {
            return "HTTP_" + re.getHttpStatus();
        }
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException wcre) {
                return "HTTP_" + wcre.getStatusCode().value();
            }
        }
        return "UPSTREAM_ERROR";
    }

    /** 访问日志/健康上报状态码: RelayException / WCRE (含 cause 链) 的真实状态, 无从判断时 502. */
    public static int httpStatusOf(Throwable err) {
        if (err instanceof RelayException re) {
            return re.getHttpStatus();
        }
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException wcre) {
                return wcre.getStatusCode().value();
            }
        }
        return 502;
    }

    /**
     * 归一为 RelayException: 已是 RelayException 原样返回; WCRE → 真实状态
     * (信封业务码按 {@link RelayException} 默认映射, 如 401→10202 / 429→10429 段);
     * 其余 (网络级故障) → 502. cause 链保留.
     */
    public static RelayException wrap(Throwable err) {
        if (err instanceof RelayException re) {
            return re;
        }
        if (err instanceof WebClientResponseException wcre) {
            int status = wcre.getStatusCode().value();
            return new RelayException(status,
                    "upstream error HTTP_" + status + ": " + excerpt(wcre.getResponseBodyAsString()), err);
        }
        return new RelayException(502, "upstream failed: " + err.getMessage(), err);
    }

    /**
     * 200+错误载荷软失败判定: 顶层 {@code error} 为 Map 即软失败 (调用点放在协议转换之前,
     * OpenAI/Anthropic 两种形态通吃). 携带 status 字段用真实状态, 缺失/非法按 502.
     *
     * @throws RelayException 载荷为错误体时
     */
    public static void throwIfSoftError(Map<String, Object> body) {
        if (body == null || !(body.get("error") instanceof Map<?, ?> err)) {
            return;
        }
        int status = err.get("status") instanceof Number n ? n.intValue() : 502;
        if (status < 400) {
            status = 502;
        }
        Object msg = err.get("message");
        throw new RelayException(status,
                "upstream soft error HTTP_" + status + ": " + (msg == null ? "-" : msg));
    }

    private static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return "-";
        }
        String oneLine = body.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "...";
    }
}
