package fun.commons.tokengateway.framework;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * API 错误码枚举 (从 framework4j-api v1.2.1 复制, 自包含, 避免引整个 framework4j-all).
 *
 * <p>段位划分 (对齐 mc-api-spec v1.6 §7):
 * <ul>
 *   <li>0        成功</li>
 *   <li>10xxx    系统与基础设施</li>
 *   <li>101xx    请求与参数校验</li>
 *   <li>102xx    认证</li>
 *   <li>103xx    权限</li>
 *   <li>104xx    资源</li>
 *   <li>105xx    流量控制 + 文件上传</li>
 *   <li>106xx    业务自定义</li>
 *   <li>10700    部分成功</li>
 * </ul>
 */
@Getter
@AllArgsConstructor
public enum ApiCode {

    SUCCESS(0, "操作成功"),

    SYSTEM_BUSY(10001, "系统繁忙，请稍后再试"),
    SERVICE_MAINTENANCE(10002, "服务暂停维护"),
    SERVICE_TIMEOUT(10003, "服务调用超时"),
    THIRD_PARTY_ERROR(10004, "第三方服务异常"),
    MIDDLEWARE_ERROR(10005, "中间件服务异常"),

    PARAM_ERROR(10100, "请求参数错误"),
    REQUIRED_MISSING(10101, "必填参数缺失"),
    FORMAT_INVALID(10102, "参数格式不正确"),
    OUT_OF_RANGE(10103, "参数取值范围错误"),
    JSON_PARSE_ERROR(10104, "JSON 解析错误"),
    BUSINESS_RULE_ERROR(10106, "业务规则校验失败"),

    UNAUTHORIZED(10200, "未认证或令牌已过期"),
    TOKEN_EXPIRED(10201, "令牌已过期"),
    TOKEN_INVALID(10202, "令牌无效"),

    FORBIDDEN(10300, "权限不足"),

    NOT_FOUND(10400, "资源不存在"),
    UNIQUE_CONFLICT(10401, "数据已存在"),
    STATE_CONFLICT(10402, "状态冲突"),

    TOO_MANY_REQUESTS(10500, "请求过频"),
    DUPLICATE_SUBMIT(10501, "重复提交"),

    INSUFFICIENT_BALANCE(10617, "余额不足"),

    PARTIAL_SUCCESS(10700, "部分成功");

    private final int code;
    private final String message;
}
