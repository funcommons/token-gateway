package fun.commons.tokengateway.exception;

import fun.commons.tokengateway.config.ErrorContractProperties;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

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
 *
 * <p>错误形状分流 (issue #24): {@code gateway.error-shape=openai} 时, LLM 面 path
 * ({@code /v1/chat} / {@code /v1/messages} 前缀) 的错误响应改以 OpenAI error 形状输出
 * (组装见 {@link OpenAiErrorAssembler}); 任务面 / 内部端点 / 其余 LLM 面 path 恒为信封
 * (开关不误伤)。白名单透传 (gateway.error-passthrough-codes) 在 RelayOrchestrator
 * 失败分支即转为 RelayException(透传状态, 原码, 原始 message), 两种形状均自然生效。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ErrorContractProperties errorContract;

    /** 兼容构造 (测试/存量装配): 默认错误契约 (envelope + 默认透传白名单). */
    public GlobalExceptionHandler() {
        this(new ErrorContractProperties());
    }

    /** Spring 装配: gateway.error-shape / gateway.error-passthrough-codes (issue #24). */
    @Autowired
    public GlobalExceptionHandler(ErrorContractProperties errorContract) {
        this.errorContract = errorContract;
    }

    @ExceptionHandler(RelayException.class)
    public ResponseEntity<Object> handleRelay(RelayException ex, ServerWebExchange exchange) {
        log.warn("[RelayException] status={}, code={}, msg={}", ex.getHttpStatus(), ex.getCode(), ex.getMessage());
        return respond(resolveHttpStatus(ex.getHttpStatus()), ex.getCode(), ex.getMessage(), exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> handleResponseStatus(ResponseStatusException ex,
                                                        ServerWebExchange exchange) {
        log.warn("[ResponseStatusException] status={}, msg={}", ex.getStatusCode(), ex.getMessage());
        int code = ex.getStatusCode().value();
        HttpStatus status = resolveHttpStatus(code);
        return respond(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status,
                code, ex.getReason(), exchange);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleAny(Exception ex, ServerWebExchange exchange) {
        log.error("[Unhandled] 未处理异常", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiCode.SYSTEM_BUSY.getCode(),
                "系统繁忙: " + ex.getMessage(), exchange);
    }

    /**
     * 错误形状出口分流 (issue #24): openai 形状只对 LLM 面 path 生效
     * ({@link OpenAiErrorAssembler#isLlmFacePath}), 其余一律走既有信封 —
     * 默认 envelope 模式全量行为不变.
     */
    private ResponseEntity<Object> respond(HttpStatus status, int code, String message,
                                           ServerWebExchange exchange) {
        if (errorContract.isOpenAiShape() && OpenAiErrorAssembler.isLlmFacePath(
                exchange.getRequest().getPath().value())) {
            return ResponseEntity.status(status).body(OpenAiErrorAssembler.assemble(
                    status.value(), code, message, OpenAiErrorAssembler.currentTraceId(exchange)));
        }
        return ResponseEntity.status(status).body(ApiResponse.fail(code, message));
    }

    private HttpStatus resolveHttpStatus(int code) {
        if (code >= 400 && code <= 599) {
            return HttpStatus.resolve(code);
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
