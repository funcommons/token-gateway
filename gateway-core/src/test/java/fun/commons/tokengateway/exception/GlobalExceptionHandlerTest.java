package fun.commons.tokengateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import fun.commons.tokengateway.config.ErrorContractProperties;
import fun.commons.tokengateway.exception.OpenAiErrorAssembler.Body;
import fun.commons.tokengateway.framework.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单测.
 *
 * <p>验证不同异常类型 → 正确 HTTP 状态码 + 信封格式; 以及 issue #24 错误形状分流:
 * 默认 envelope 全量行为不变, openai 形状只对 LLM 面 path (/v1/chat, /v1/messages 前缀) 生效.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final GlobalExceptionHandler openAiHandler = openAiHandler();

    private static GlobalExceptionHandler openAiHandler() {
        var props = new ErrorContractProperties();
        props.setErrorShape(ErrorContractProperties.ErrorShape.OPENAI);
        return new GlobalExceptionHandler(props);
    }

    /** 默认 (envelope) 模式信封断言辅助. */
    @SuppressWarnings("unchecked")
    private static ApiResponse<Object> envelope(ResponseEntity<Object> resp) {
        return (ApiResponse<Object>) resp.getBody();
    }

    private static ServerWebExchange exchangeOf(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }

    @Test
    @DisplayName("RelayException(401) → HTTP 401 + 信封业务码 10202 (默认映射)")
    void relayException401() {
        var resp = handler.handleRelay(new RelayException(401, "invalid token"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(envelope(resp).getCode()).isEqualTo(10202);
        assertThat(envelope(resp).getMessage()).isEqualTo("invalid token");
    }

    @Test
    @DisplayName("RelayException(401, 10200) → 显式业务码优先 (缺少凭证=未认证)")
    void relayException401ExplicitCode() {
        var resp = handler.handleRelay(new RelayException(401, 10200, "缺少 bearer token"),
                exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(envelope(resp).getCode()).isEqualTo(10200);
    }

    @Test
    @DisplayName("RelayException(400) → HTTP 400 + 信封业务码 10100")
    void relayException400() {
        var resp = handler.handleRelay(new RelayException(400, "model 字段必填"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(envelope(resp).getCode()).isEqualTo(10100);
    }

    @Test
    @DisplayName("RelayException(402, 10617) → HTTP 402 + 信封业务码 10617 (余额不足)")
    void relayException402InsufficientBalance() {
        var resp = handler.handleRelay(new RelayException(402, 10617, "余额不足"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(envelope(resp).getCode()).isEqualTo(10617);
    }

    @Test
    @DisplayName("RelayException(404, 10400) → HTTP 404 + 信封业务码 10400 (无可用渠道)")
    void relayException404NoChannel() {
        var resp = handler.handleRelay(new RelayException(404, 10400, "模型不存在或无可用渠道"),
                exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(envelope(resp).getCode()).isEqualTo(10400);
    }

    @Test
    @DisplayName("RelayException(502) → HTTP 502 + 信封业务码 10004")
    void relayException502() {
        var resp = handler.handleRelay(new RelayException(502, "upstream failed"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(envelope(resp).getCode()).isEqualTo(10004);
    }

    @Test
    @DisplayName("RelayException(502, 10004, \"x\") 显式码 → HTTP 502 + 信封 10004 + message 原样")
    void relayException502ExplicitCode() {
        var resp = handler.handleRelay(new RelayException(502, 10004, "x"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(envelope(resp).getCode()).isEqualTo(10004);
        assertThat(envelope(resp).getMessage()).isEqualTo("x");
    }

    @Test
    @DisplayName("RelayException(401, 10202, \"k\") 显式码 → HTTP 401 + 信封 10202 (令牌无效)")
    void relayException401Explicit10202() {
        var resp = handler.handleRelay(new RelayException(401, 10202, "k"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(envelope(resp).getCode()).isEqualTo(10202);
        assertThat(envelope(resp).getMessage()).isEqualTo("k");
    }

    @Test
    @DisplayName("RelayException(999 越界) → HTTP 500 兜底")
    void relayExceptionOutOfRange() {
        var resp = handler.handleRelay(new RelayException(999, "test"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("ResponseStatusException → 用异常 status code")
    void responseStatusException() {
        var resp = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND, "no"),
                exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(envelope(resp).getCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("其它 Exception → HTTP 500 + SYSTEM_BUSY")
    void unknownException() {
        var resp = handler.handleAny(new RuntimeException("NPE"), exchangeOf("/v1/models"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(envelope(resp).isFail()).isTrue();
        assertThat(envelope(resp).getCode()).isEqualTo(10001);
        assertThat(envelope(resp).getMessage()).asString().contains("NPE");
    }

    // ---- issue #24: openai 错误形状 (仅 LLM 面 path 生效) ----

    @Test
    @DisplayName("openai 模式: 未处理 Exception (LLM 面 path) → error.type=api_error + error.code=10001")
    void openAiUnknownExceptionIsApiError() {
        var resp = openAiHandler.handleAny(new RuntimeException("boom"),
                exchangeOf("/v1/chat/completions"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Body body = (Body) resp.getBody();
        assertThat(body.error().type()).isEqualTo("api_error");
        assertThat(body.error().code()).isEqualTo("10001");
        assertThat(body.error().message()).asString().contains("boom");
        assertThat(body.traceId()).isNotBlank();
    }

    @Test
    @DisplayName("openai 模式: 401 → authentication_error + error.code=10202 (chat completions)")
    void openAi401AuthenticationError() {
        var resp = openAiHandler.handleRelay(new RelayException(401, "invalid token"),
                exchangeOf("/v1/chat/completions"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Body body = (Body) resp.getBody();
        assertThat(body.error().type()).isEqualTo("authentication_error");
        assertThat(body.error().code()).isEqualTo("10202");
        assertThat(body.error().message()).isEqualTo("invalid token");
        assertThat(body.error().param()).isNull();
        assertThat(body.traceId()).isNotBlank();
    }

    @Test
    @DisplayName("openai 模式: 404 → not_found_error + error.code=10400")
    void openAi404NotFoundError() {
        var resp = openAiHandler.handleRelay(new RelayException(404, 10400, "模型不存在或无可用渠道"),
                exchangeOf("/v1/messages"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Body body = (Body) resp.getBody();
        assertThat(body.error().type()).isEqualTo("not_found_error");
        assertThat(body.error().code()).isEqualTo("10400");
    }

    @Test
    @DisplayName("openai 模式: 402 → insufficient_quota + error.code=10617 (余额)")
    void openAi402InsufficientQuota() {
        var resp = openAiHandler.handleRelay(new RelayException(402, 10617, "余额不足"),
                exchangeOf("/v1/chat/completions"));
        Body body = (Body) resp.getBody();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(body.error().type()).isEqualTo("insufficient_quota");
        assertThat(body.error().code()).isEqualTo("10617");
    }

    @Test
    @DisplayName("openai 模式: 429 → rate_limit_error")
    void openAi429RateLimitError() {
        var resp = openAiHandler.handleRelay(new RelayException(429, 10500, "请求过频"),
                exchangeOf("/v1/messages"));
        assertThat(((Body) resp.getBody()).error().type()).isEqualTo("rate_limit_error");
    }

    @Test
    @DisplayName("openai 模式: 400 → invalid_request_error")
    void openAi400InvalidRequest() {
        var resp = openAiHandler.handleRelay(new RelayException(400, "model 字段必填"),
                exchangeOf("/v1/chat/completions"));
        assertThat(((Body) resp.getBody()).error().type()).isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("openai 模式: 502 → api_error + error.code=10004")
    void openAi5xxApiError() {
        var resp = openAiHandler.handleRelay(new RelayException(502, "upstream failed"),
                exchangeOf("/v1/chat/completions"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        Body body = (Body) resp.getBody();
        assertThat(body.error().type()).isEqualTo("api_error");
        assertThat(body.error().code()).isEqualTo("10004");
    }

    @Test
    @DisplayName("openai 模式 + 白名单透传组合: 403+4090 → permission_error + error.code=4090")
    void openAiPassthroughCombo() {
        var resp = openAiHandler.handleRelay(new RelayException(403, 4090, "风控拒绝"),
                exchangeOf("/v1/chat/completions"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Body body = (Body) resp.getBody();
        assertThat(body.error().type()).isEqualTo("permission_error");
        assertThat(body.error().code()).isEqualTo("4090");
        assertThat(body.error().message()).isEqualTo("风控拒绝");
    }

    @Test
    @DisplayName("openai 模式: 任务面 path (/v1/tasks/submit) 仍信封 — 开关不误伤")
    void taskFacePathStaysEnvelope() {
        var resp = openAiHandler.handleRelay(new RelayException(502, "upstream failed"),
                exchangeOf("/v1/tasks/submit"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(envelope(resp).getCode()).isEqualTo(10004);
    }

    @Test
    @DisplayName("openai 模式: 任务面 path (/v1/tasks/abc) 仍信封")
    void taskAbcPathStaysEnvelope() {
        var resp = openAiHandler.handleRelay(new RelayException(401, "invalid token"),
                exchangeOf("/v1/tasks/abc"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(envelope(resp).getCode()).isEqualTo(10202);
    }

    @Test
    @DisplayName("openai 模式: 内部端点 (/internal/**) 恒信封 — 开关不误伤")
    void internalPathStaysEnvelope() {
        var resp = openAiHandler.handleRelay(new RelayException(502, "upstream failed"),
                exchangeOf("/internal/ops/flush-cache"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(envelope(resp).getCode()).isEqualTo(10004);
    }

    @Test
    @DisplayName("openai 模式: 非 chat/messages LLM 面 path (/v1/models, /v1/embeddings) 仍信封")
    void otherLlmPathsStaysEnvelope() {
        var respModels = openAiHandler.handleRelay(new RelayException(502, "upstream failed"),
                exchangeOf("/v1/models"));
        var respEmbed = openAiHandler.handleRelay(new RelayException(502, "upstream failed"),
                exchangeOf("/v1/embeddings"));
        assertThat(envelope(respModels).getCode()).isEqualTo(10004);
        assertThat(envelope(respEmbed).getCode()).isEqualTo(10004);
    }

    @Test
    @DisplayName("openai 模式: count_tokens (/v1/messages 前缀) 走 openai 形状")
    void countTokensMatchesMessagesPrefix() {
        var resp = openAiHandler.handleRelay(new RelayException(400, "model 字段必填"),
                exchangeOf("/v1/messages/count_tokens"));
        assertThat(((Body) resp.getBody()).error().type()).isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("openai 模式: 线格式 = {error:{message,type,param,code}, trace_id} (顶层 trace_id)")
    void openAiWireFormat() throws Exception {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Trace-Id", "trace-fixed-1").build());
        exchange.getResponse().getHeaders().set("X-Trace-Id", "trace-fixed-1");
        var resp = openAiHandler.handleRelay(new RelayException(401, "invalid token"), exchange);
        String json = new ObjectMapper().writeValueAsString(resp.getBody());
        assertThat(json).contains("\"trace_id\":\"trace-fixed-1\"");
        assertThat(json).contains("\"param\":null");
        assertThat(json).contains("\"type\":\"authentication_error\"");
        assertThat(json).contains("\"code\":\"10202\"");
    }
}
