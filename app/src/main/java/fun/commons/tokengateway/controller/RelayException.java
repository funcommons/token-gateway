package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.framework.ApiCode;

/**
 * 业务异常, 承载 HTTP 状态码 + 业务错误码 ({@link ApiCode} 段位).
 * 由 GlobalExceptionHandler 兜底转 JSON 信封响应: HTTP 状态按 httpStatus, 信封 code 按 code.
 *
 * <p>抽取自 ChatCompletionController 内部静态类, MessagesController / ModelsController 等
 * 共用同一个异常类型, 避免到处定义独立异常.
 *
 * <p>信封 code 对齐 docs/用户文档/01_LLM面接入手册.md §7 错误码表
 * (10200 未认证 / 10202 令牌无效 / 10400 无可用渠道 / 10617 余额不足 ...).
 */
public class RelayException extends RuntimeException {

    private final int httpStatus;
    private final int code;

    /** 信封业务码按 HTTP 状态默认映射 (见 {@link #defaultCodeOf}). */
    public RelayException(int httpStatus, String message) {
        this(httpStatus, defaultCodeOf(httpStatus), message);
    }

    /** 显式指定信封业务码 (抛出点语义比默认映射更精确时用, 如 10200/10101/10617). */
    public RelayException(int httpStatus, int code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public int getCode() {
        return code;
    }

    /**
     * HTTP 状态 → 信封业务码默认映射. 映射不到时按 4xx=PARAM_ERROR / 5xx=SYSTEM_BUSY 归段.
     */
    static int defaultCodeOf(int httpStatus) {
        return switch (httpStatus) {
            case 400 -> ApiCode.PARAM_ERROR.getCode();
            case 401 -> ApiCode.TOKEN_INVALID.getCode();
            case 403 -> ApiCode.FORBIDDEN.getCode();
            case 404 -> ApiCode.NOT_FOUND.getCode();
            case 409 -> ApiCode.STATE_CONFLICT.getCode();
            case 429 -> ApiCode.TOO_MANY_REQUESTS.getCode();
            case 500 -> ApiCode.SYSTEM_BUSY.getCode();
            case 502 -> ApiCode.THIRD_PARTY_ERROR.getCode();
            case 503 -> ApiCode.SERVICE_MAINTENANCE.getCode();
            case 504 -> ApiCode.SERVICE_TIMEOUT.getCode();
            default -> httpStatus >= 400 && httpStatus < 500
                    ? ApiCode.PARAM_ERROR.getCode() : ApiCode.SYSTEM_BUSY.getCode();
        };
    }
}
