package fun.commons.tokengateway.exception;

import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器 (WebFlux).
 *
 * <p>把 Controller 抛出的异常转成统一信封 {@link ApiResponse}, HTTP 状态码按业务映射.
 *
 * <p>异常类型:
 * <ul>
 *   <li>{@link RelayException} → HTTP 状态用异常携带的 httpStatus, 信封 code 用业务码
 *   (ApiCode 段位, 对齐用户手册 §7 错误码表)</li>
 *   <li>{@link ResponseStatusException} → 用异常的 status code</li>
 *   <li>其它 RuntimeException → 500 + SYSTEM_BUSY</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RelayException.class)
    public ResponseEntity<ApiResponse<Object>> handleRelay(RelayException ex) {
        log.warn("[RelayException] status={}, code={}, msg={}", ex.getHttpStatus(), ex.getCode(), ex.getMessage());
        HttpStatus status = resolveHttpStatus(ex.getHttpStatus());
        ApiResponse<Object> body = ApiResponse.fail(ex.getCode(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Object>> handleResponseStatus(ResponseStatusException ex) {
        log.warn("[ResponseStatusException] status={}, msg={}", ex.getStatusCode(), ex.getMessage());
        int code = ex.getStatusCode().value();
        ApiResponse<Object> body = ApiResponse.fail(code, ex.getReason());
        return ResponseEntity.status(code).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleAny(Exception ex) {
        log.error("[Unhandled] 未处理异常", ex);
        ApiResponse<Object> body = ApiResponse.fail(ApiCode.SYSTEM_BUSY.getCode(),
                "系统繁忙: " + ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private HttpStatus resolveHttpStatus(int code) {
        if (code >= 400 && code <= 599) {
            return HttpStatus.resolve(code);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
