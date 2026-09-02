package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.framework.ApiCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UpstreamErrorPolicy 测试 (issue #1): 软失败识别 / 真实状态码提取 / errorCode 归类 / 异常包装.
 */
@DisplayName("UpstreamErrorPolicy: 软失败识别 + 状态码归类")
class UpstreamErrorPolicyTest {

    private static WebClientResponseException wcre(int status, String statusText, String body) {
        return WebClientResponseException.create(status, statusText, HttpHeaders.EMPTY,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("errorCodeOf: RelayException → HTTP_<status>")
    void errorCodeFromRelayException() {
        assertThat(UpstreamErrorPolicy.errorCodeOf(new RelayException(429, "rate limited")))
                .isEqualTo("HTTP_429");
    }

    @Test
    @DisplayName("errorCodeOf: WCRE 直接/嵌 cause 链 → HTTP_<status>; 无从判断 → UPSTREAM_ERROR")
    void errorCodeFromCauseChain() {
        assertThat(UpstreamErrorPolicy.errorCodeOf(wcre(401, "Unauthorized", null)))
                .isEqualTo("HTTP_401");
        IllegalStateException wrapped = new IllegalStateException("relay",
                wcre(500, "Internal Server Error", null));
        assertThat(UpstreamErrorPolicy.errorCodeOf(wrapped)).isEqualTo("HTTP_500");
        assertThat(UpstreamErrorPolicy.errorCodeOf(new RuntimeException("connect timeout")))
                .isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    @DisplayName("httpStatusOf: RelayException / cause 链 WCRE / 兜底 502")
    void httpStatusExtraction() {
        assertThat(UpstreamErrorPolicy.httpStatusOf(new RelayException(401, "bad key"))).isEqualTo(401);
        assertThat(UpstreamErrorPolicy.httpStatusOf(new RuntimeException("wrap",
                wcre(429, "Too Many Requests", null)))).isEqualTo(429);
        assertThat(UpstreamErrorPolicy.httpStatusOf(new RuntimeException("boom"))).isEqualTo(502);
    }

    @Test
    @DisplayName("wrap: RelayException 原样返回 (同一实例)")
    void wrapPassesThroughRelayException() {
        RelayException original = new RelayException(429, "soft error");
        assertThat(UpstreamErrorPolicy.wrap(original)).isSameAs(original);
    }

    @Test
    @DisplayName("wrap: WCRE → 真实状态 + 默认业务码映射 + cause 保留 + body 摘要")
    void wrapExtractsRealStatus() {
        RelayException wrapped = UpstreamErrorPolicy.wrap(wcre(401, "Unauthorized", "{\"error\":null}"));
        assertThat(wrapped.getHttpStatus()).isEqualTo(401);
        assertThat(wrapped.getCode()).isEqualTo(ApiCode.TOKEN_INVALID.getCode());
        assertThat(wrapped.getMessage()).contains("HTTP_401").contains("\"error\":null");
        assertThat(wrapped.getCause()).isInstanceOf(WebClientResponseException.class);
    }

    @Test
    @DisplayName("wrap: 网络级故障 → 502 THIRD_PARTY_ERROR")
    void wrapDefaultsTo502() {
        RelayException wrapped = UpstreamErrorPolicy.wrap(new RuntimeException("connection reset"));
        assertThat(wrapped.getHttpStatus()).isEqualTo(502);
        assertThat(wrapped.getCode()).isEqualTo(ApiCode.THIRD_PARTY_ERROR.getCode());
        assertThat(wrapped.getMessage()).contains("connection reset");
    }

    @Test
    @DisplayName("软失败: OpenAI 错误体 (带 status) → 按真实状态上抛")
    void softErrorWithStatus() {
        Map<String, Object> body = Map.of("error", Map.of(
                "message", "You exceeded your current quota",
                "type", "insufficient_quota",
                "status", 429));
        assertThatThrownBy(() -> UpstreamErrorPolicy.throwIfSoftError(body))
                .isInstanceOf(RelayException.class)
                .satisfies(e -> {
                    RelayException re = (RelayException) e;
                    assertThat(re.getHttpStatus()).isEqualTo(429);
                    assertThat(re.getCode()).isEqualTo(ApiCode.TOO_MANY_REQUESTS.getCode());
                    assertThat(re.getMessage()).contains("You exceeded your current quota");
                });
    }

    @Test
    @DisplayName("软失败: Anthropic 错误体 (无 status) → 兜底 502")
    void softErrorWithoutStatusFallsBackTo502() {
        Map<String, Object> body = Map.of(
                "type", "error",
                "error", Map.of("type", "authentication_error", "message", "invalid x-api-key"));
        assertThatThrownBy(() -> UpstreamErrorPolicy.throwIfSoftError(body))
                .isInstanceOf(RelayException.class)
                .satisfies(e -> {
                    RelayException re = (RelayException) e;
                    assertThat(re.getHttpStatus()).isEqualTo(502);
                    assertThat(re.getMessage()).contains("invalid x-api-key");
                });
    }

    @Test
    @DisplayName("软失败: error.status 非法 (<400) → 兜底 502")
    void softErrorWithBogusStatusFallsBackTo502() {
        Map<String, Object> body = Map.of("error", Map.of("status", 200, "message", "weird proxy"));
        assertThatThrownBy(() -> UpstreamErrorPolicy.throwIfSoftError(body))
                .isInstanceOf(RelayException.class)
                .satisfies(e -> assertThat(((RelayException) e).getHttpStatus()).isEqualTo(502));
    }

    @Test
    @DisplayName("正常载荷不误判: 无 error 键 / error 非 Map / null body 均放行")
    void normalPayloadsPassThrough() {
        Map<String, Object> success = new HashMap<>();
        success.put("id", "chatcmpl-1");
        success.put("object", "chat.completion");
        assertThatCode(() -> UpstreamErrorPolicy.throwIfSoftError(success)).doesNotThrowAnyException();
        assertThatCode(() -> UpstreamErrorPolicy.throwIfSoftError(
                Map.of("error", "plain string"))).doesNotThrowAnyException();
        assertThatCode(() -> UpstreamErrorPolicy.throwIfSoftError(null)).doesNotThrowAnyException();
    }
}
