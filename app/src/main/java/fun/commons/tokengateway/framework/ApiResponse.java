package fun.commons.tokengateway.framework;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.List;
import java.util.UUID;

/**
 * 统一 API 响应结构 (从 framework4j-web v1.2.1 复制, 自包含, 不依赖 framework4j 全栈).
 *
 * <p>标准响应格式 (对齐 mc-api-spec v1.6 §4):
 * <pre>
 * {
 *   "code": 0,
 *   "message": "操作成功",
 *   "data": { ... },
 *   "error": null,
 *   "trace_id": "uuid",
 *   "timestamp": 1718660400000
 * }
 * </pre>
 *
 * <p>用于反序列化主应用 (bootstrap) 返回的 ApiResponse JSON — 跨服务 RPC 场景.
 *
 * @param <T> 响应数据类型
 */
@Getter
public class ApiResponse<T> {

    private final int code;
    private final String message;
    private final T data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private final List<ApiError> error;

    @JsonProperty("trace_id")
    private final String traceId;

    @JsonProperty("timestamp")
    private final long timestamp;

    @JsonCreator
    public ApiResponse(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") T data,
            @JsonProperty("error") List<ApiError> error,
            @JsonProperty("trace_id") String traceId,
            @JsonProperty("timestamp") Long timestamp) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.error = error;
        this.traceId = traceId;
        this.timestamp = timestamp != null ? timestamp : 0L;
    }

    private ApiResponse(int code, String message, T data, List<ApiError> error, boolean autoTrace) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.error = error;
        this.traceId = autoTrace ? UUID.randomUUID().toString() : null;
        this.timestamp = autoTrace ? System.currentTimeMillis() : 0L;
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(ApiCode.SUCCESS.getCode(), ApiCode.SUCCESS.getMessage(), null, null, true);
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ApiCode.SUCCESS.getCode(), ApiCode.SUCCESS.getMessage(), data, null, true);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(ApiCode.SUCCESS.getCode(), message, data, null, true);
    }

    public static <T> ApiResponse<T> fail(ApiCode apiCode) {
        return new ApiResponse<>(apiCode.getCode(), apiCode.getMessage(), null, null, true);
    }

    public static <T> ApiResponse<T> fail(ApiCode apiCode, String message) {
        return new ApiResponse<>(apiCode.getCode(), message, null, null, true);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null, true);
    }

    public boolean isSuccess() {
        return this.code == 0;
    }

    public boolean isFail() {
        return this.code != 0;
    }
}
