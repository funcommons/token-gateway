package fun.commons.tokengateway.framework;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 字段级错误详情 (从 framework4j-web v1.2.1 复制, 自包含).
 *
 * <p>对齐 mc-api-spec v1.6 §4: 失败响应的 error 字段为 List&lt;ApiError&gt;,
 * 每项含 field / code / message / rejectedValue.
 *
 * <p>rejectedValue 序列化时自动截断到 100 字符, 防大对象 / 敏感信息泄露.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String field,
        String code,
        String message,
        @JsonProperty("rejectedValue") Object rejectedValue
) {

    private static final int REJECTED_VALUE_MAX_LEN = 100;

    public static ApiError of(String field, String message) {
        return new ApiError(field, null, message, null);
    }

    public static ApiError of(String field, String code, String message) {
        return new ApiError(field, code, message, null);
    }

    public static ApiError of(String field, String code, String message, Object rejectedValue) {
        return new ApiError(field, code, message, sanitizeRejectedValue(rejectedValue));
    }

    private static Object sanitizeRejectedValue(Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence cs) {
            String s = cs.toString();
            return s.length() > REJECTED_VALUE_MAX_LEN ? s.substring(0, REJECTED_VALUE_MAX_LEN) + "..." : s;
        }
        return value;
    }
}
