package fun.commons.tokengateway.exception;

import fun.commons.tokengateway.controller.RelayException;
import fun.commons.tokengateway.framework.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单测.
 *
 * <p>验证不同异常类型 → 正确 HTTP 状态码 + 信封格式.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("RelayException(401) → HTTP 401 + 信封业务码 10202 (默认映射)")
    void relayException401() {
        var resp = handler.handleRelay(new RelayException(401, "invalid token"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().getCode()).isEqualTo(10202);
        assertThat(resp.getBody().getMessage()).isEqualTo("invalid token");
    }

    @Test
    @DisplayName("RelayException(401, 10200) → 显式业务码优先 (缺少凭证=未认证)")
    void relayException401ExplicitCode() {
        var resp = handler.handleRelay(new RelayException(401, 10200, "缺少 bearer token"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody().getCode()).isEqualTo(10200);
    }

    @Test
    @DisplayName("RelayException(400) → HTTP 400 + 信封业务码 10100")
    void relayException400() {
        var resp = handler.handleRelay(new RelayException(400, "model 字段必填"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().getCode()).isEqualTo(10100);
    }

    @Test
    @DisplayName("RelayException(402, 10617) → HTTP 402 + 信封业务码 10617 (余额不足)")
    void relayException402InsufficientBalance() {
        var resp = handler.handleRelay(new RelayException(402, 10617, "余额不足"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(resp.getBody().getCode()).isEqualTo(10617);
    }

    @Test
    @DisplayName("RelayException(404, 10400) → HTTP 404 + 信封业务码 10400 (无可用渠道)")
    void relayException404NoChannel() {
        var resp = handler.handleRelay(new RelayException(404, 10400, "模型不存在或无可用渠道"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getCode()).isEqualTo(10400);
    }

    @Test
    @DisplayName("RelayException(502) → HTTP 502 + 信封业务码 10004")
    void relayException502() {
        var resp = handler.handleRelay(new RelayException(502, "upstream failed"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody().getCode()).isEqualTo(10004);
    }

    @Test
    @DisplayName("RelayException(999 越界) → HTTP 500 兜底")
    void relayExceptionOutOfRange() {
        var resp = handler.handleRelay(new RelayException(999, "test"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("ResponseStatusException → 用异常 status code")
    void responseStatusException() {
        var resp = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "no"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("其它 Exception → HTTP 500 + SYSTEM_BUSY")
    void unknownException() {
        var resp = handler.handleAny(new RuntimeException("NPE"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().isFail()).isTrue();
        assertThat(resp.getBody().getMessage()).asString().contains("NPE");
    }
}
