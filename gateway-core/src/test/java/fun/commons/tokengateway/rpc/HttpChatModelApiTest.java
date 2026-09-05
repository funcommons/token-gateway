package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.framework.ApiCode;
import fun.commons.tokengateway.framework.ApiResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HttpChatModelApi 测试.
 */
@DisplayName("HttpChatModelApi")
class HttpChatModelApiTest {

    private MockWebServer backend;
    private HttpChatModelApi api;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        props.setTimeout(Duration.ofSeconds(2));
        api = new HttpChatModelApi(WebClient.builder(), new CapabilityEndpoints(new TokenGatewayProperties(), props), new RpcInternalAuth(props));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    @Test
    @DisplayName("listEnabledModels 成功: 200 + list 字段解析")
    void listSuccess() throws Exception {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":[{\"id\":\"gpt-4o\",\"displayName\":\"GPT-4o\",\"owner\":\"openai\"},"
                        + "{\"id\":\"claude-3\",\"displayName\":\"Claude 3\",\"owner\":\"anthropic\"}]}"));

        StepVerifier.create(api.listEnabledModels(null))
                .assertNext(resp -> {
                    assertThat(resp.isSuccess()).isTrue();
                    assertThat(resp.getData()).hasSize(2);
                    assertThat(resp.getData().get(0).get("id")).isEqualTo("gpt-4o");
                })
                .verifyComplete();

        var recorded = backend.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/api/v1/internal/chat-models");
        assertThat(recorded.getMethod()).isEqualTo("GET");
    }

    @Test
    @DisplayName("RPC 失败 (上游 500) → onErrorResume 降级")
    void upstreamError() {
        backend.enqueue(new MockResponse().setResponseCode(500).setBody("server down"));

        StepVerifier.create(api.listEnabledModels(null))
                .assertNext(resp -> {
                    assertThat(resp.isFail()).isTrue();
                    assertThat(resp.getCode()).isEqualTo(ApiCode.SERVICE_TIMEOUT.getCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("空列表 → 200 + data=[]")
    void emptyList() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":[]}"));

        StepVerifier.create(api.listEnabledModels(null))
                .assertNext(resp -> {
                    assertThat(resp.isSuccess()).isTrue();
                    assertThat(resp.getData()).isEmpty();
                })
                .verifyComplete();
    }
}
