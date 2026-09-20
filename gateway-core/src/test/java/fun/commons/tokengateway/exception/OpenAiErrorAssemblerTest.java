package fun.commons.tokengateway.exception;

import fun.commons.tokengateway.exception.OpenAiErrorAssembler.Body;
import fun.commons.tokengateway.exception.OpenAiErrorAssembler.Payload;
import fun.commons.tokengateway.trace.TraceWebFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiErrorAssembler 单测 (issue #24): LLM 面 path 判定 / HTTP 状态 → error.type 映射 /
 * assemble 信封三要素保真 (code 字符串化 + message 原样 + trace_id 顶层) / trace 链路取值.
 */
@DisplayName("OpenAiErrorAssembler")
class OpenAiErrorAssemblerTest {

    // ---- isLlmFacePath: 开关只对 /v1/chat 与 /v1/messages 前缀生效 ----

    @Test
    @DisplayName("isLlmFacePath: null → false (防御)")
    void nullPathIsNotLlmFace() {
        assertThat(OpenAiErrorAssembler.isLlmFacePath(null)).isFalse();
    }

    @Test
    @DisplayName("isLlmFacePath: /v1/chat/completions → true")
    void chatCompletionsIsLlmFace() {
        assertThat(OpenAiErrorAssembler.isLlmFacePath("/v1/chat/completions")).isTrue();
    }

    @Test
    @DisplayName("isLlmFacePath: /v1/chatx → true (startsWith 前缀语义, 实现即真源)")
    void chatPrefixIsLlmFace() {
        assertThat(OpenAiErrorAssembler.isLlmFacePath("/v1/chatx")).isTrue();
    }

    @Test
    @DisplayName("isLlmFacePath: /v1/messages 与 /v1/messages/count_tokens → true")
    void messagesPrefixIsLlmFace() {
        assertThat(OpenAiErrorAssembler.isLlmFacePath("/v1/messages")).isTrue();
        assertThat(OpenAiErrorAssembler.isLlmFacePath("/v1/messages/count_tokens")).isTrue();
    }

    @Test
    @DisplayName("isLlmFacePath: 任务面/其余 LLM 面 path → false (/v1/models, /v1/tasks)")
    void nonLlmFacePaths() {
        assertThat(OpenAiErrorAssembler.isLlmFacePath("/v1/models")).isFalse();
        assertThat(OpenAiErrorAssembler.isLlmFacePath("/v1/tasks")).isFalse();
    }

    // ---- typeOf: HTTP 状态 → OpenAI error.type 一一对应 ----

    @Test
    @DisplayName("typeOf: 401/402/403/404/429 → 对应 OpenAI 原生语义 type")
    void typeOfMappedStatuses() {
        assertThat(OpenAiErrorAssembler.typeOf(401)).isEqualTo("authentication_error");
        assertThat(OpenAiErrorAssembler.typeOf(402)).isEqualTo("insufficient_quota");
        assertThat(OpenAiErrorAssembler.typeOf(403)).isEqualTo("permission_error");
        assertThat(OpenAiErrorAssembler.typeOf(404)).isEqualTo("not_found_error");
        assertThat(OpenAiErrorAssembler.typeOf(429)).isEqualTo("rate_limit_error");
    }

    @Test
    @DisplayName("typeOf: 其余 4xx (400, 418) → invalid_request_error")
    void typeOfOther4xx() {
        assertThat(OpenAiErrorAssembler.typeOf(400)).isEqualTo("invalid_request_error");
        assertThat(OpenAiErrorAssembler.typeOf(418)).isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("typeOf: 5xx (500, 502, 599) → api_error")
    void typeOf5xx() {
        assertThat(OpenAiErrorAssembler.typeOf(500)).isEqualTo("api_error");
        assertThat(OpenAiErrorAssembler.typeOf(502)).isEqualTo("api_error");
        assertThat(OpenAiErrorAssembler.typeOf(599)).isEqualTo("api_error");
    }

    // ---- assemble: 信封三要素不丢失 ----

    @Test
    @DisplayName("assemble: code 字符串化 + message 原样 + param=null + traceId 顶层")
    void assemblePreservesEnvelopeContract() {
        Body body = OpenAiErrorAssembler.assemble(401, 10202, "令牌无效", "tid-1");
        Payload error = body.error();
        assertThat(error.code()).isEqualTo("10202").isInstanceOf(String.class);
        assertThat(error.message()).isEqualTo("令牌无效");
        assertThat(error.type()).isEqualTo("authentication_error");
        assertThat(error.param()).isNull();
        assertThat(body.traceId()).isEqualTo("tid-1");
    }

    @Test
    @DisplayName("assemble: 403+4090 白名单透传组合 → permission_error + 原码字符串")
    void assemblePassthroughCombo() {
        Body body = OpenAiErrorAssembler.assemble(403, 4090, "风控拒绝", "tid-2");
        assertThat(body.error().type()).isEqualTo("permission_error");
        assertThat(body.error().code()).isEqualTo("4090");
        assertThat(body.error().message()).isEqualTo("风控拒绝");
    }

    // ---- currentTraceId: 响应头同源, 缺失兜底 UUID ----

    @Test
    @DisplayName("currentTraceId: 响应头 X-Trace-Id 已带 → 返回同值 (与 TraceWebFilter 同源)")
    void traceIdFromResponseHeader() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        exchange.getResponse().getHeaders().set(TraceWebFilter.TRACE_ID_HEADER, "trace-fixed-9");
        assertThat(OpenAiErrorAssembler.currentTraceId(exchange)).isEqualTo("trace-fixed-9");
    }

    @Test
    @DisplayName("currentTraceId: 头缺失 → 兜底非空 UUID; exchange=null → 同样兜底")
    void traceIdFallback() {
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/v1/chat/completions").build());
        assertThat(OpenAiErrorAssembler.currentTraceId(exchange)).isNotBlank();
        assertThat(OpenAiErrorAssembler.currentTraceId(null)).isNotBlank();
    }
}
