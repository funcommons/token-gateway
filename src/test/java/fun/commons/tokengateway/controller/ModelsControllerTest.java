package fun.commons.tokengateway.controller;

import fun.commons.tokengateway.rpc.HttpChatModelApi;
import fun.commons.tokengateway.rpc.HttpTokenApi;
import fun.commons.tokengateway.rpc.RpcInternalAuth;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelsController WebFlux 原生测试.
 * <p>验证 OpenAI/Anthropic shape 切换 + token + chat-model RPC 链.
 */
@DisplayName("ModelsController WebFlux 原生")
class ModelsControllerTest {

    private MockWebServer backend;
    private ModelsController controller;

    @BeforeEach
    void setUp() throws Exception {
        backend = new MockWebServer();
        backend.start();
        var props = new fun.commons.tokengateway.config.GatewayProperties();
        props.setUrl(backend.url("/").toString().replaceAll("/$", ""));
        WebClient.Builder b = WebClient.builder();
        controller = new ModelsController(
                new HttpTokenApi(b, props, new RpcInternalAuth(props)),
                new HttpChatModelApi(b, props, new RpcInternalAuth(props)));
    }

    @AfterEach
    void tearDown() throws Exception {
        backend.shutdown();
    }

    private void mockTokenOk() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":true,\"tokenId\":\"1\"}}"));
    }

    private void mockModels() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":[{\"id\":\"gpt-4o\",\"displayName\":\"GPT-4o\",\"owner\":\"openai\"},"
                        + "{\"id\":\"claude-3\",\"displayName\":\"Claude 3\",\"owner\":\"anthropic\"}]}"));
    }

    @Test
    @DisplayName("OpenAI 客户端 (Bearer) → object=list, 字段 object/owned_by/supported_endpoint")
    void openaiShape() {
        mockTokenOk();
        mockModels();
        StepVerifier.create((Mono<?>) controller.listModels("Bearer sk", null, null))
                .assertNext(resp -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) resp;
                    assertThat(r.get("object")).isEqualTo("list");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
                    assertThat(data).hasSize(2);
                    assertThat(data.get(0).get("object")).isEqualTo("model");
                    assertThat(data.get(0).get("owned_by")).isEqualTo("openai");
                    assertThat(data.get(0).get("supported_endpoint")).isEqualTo("chat");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Anthropic 客户端 (x-api-key + anthropic-version) → data+first_id shape")
    void anthropicShape() {
        mockTokenOk();
        mockModels();
        StepVerifier.create((Mono<?>) controller.listModels(null, "sk-ant-x", "2023-06-01"))
                .assertNext(resp -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) resp;
                    assertThat(r.get("has_more")).isEqualTo(false);
                    assertThat(r.get("first_id")).isEqualTo("gpt-4o");
                    assertThat(r.get("last_id")).isEqualTo("claude-3");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> data = (List<Map<String, Object>>) r.get("data");
                    assertThat(data.get(0).get("type")).isEqualTo("model");
                    assertThat(data.get(0).get("display_name")).isEqualTo("GPT-4o");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("缺 apiKey → 401")
    void missingApiKey() {
        StepVerifier.create((Mono<?>) controller.listModels(null, null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("token 失败 → 401")
    void tokenInvalid() {
        backend.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"valid\":false}}"));
        StepVerifier.create((Mono<?>) controller.listModels("Bearer bad", null, null))
                .verifyErrorMatches(e -> e instanceof RelayException
                        && ((RelayException) e).getHttpStatus() == 401);
    }

    @Test
    @DisplayName("chat-model RPC 失败 → 空列表兜底, 不报错")
    void chatModelRpcFailureEmptyList() {
        mockTokenOk();
        backend.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create((Mono<?>) controller.listModels("Bearer sk", null, null))
                .assertNext(resp -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> r = (Map<String, Object>) resp;
                    assertThat(r.get("object")).isEqualTo("list");
                    @SuppressWarnings("unchecked")
                    List<?> data = (List<?>) r.get("data");
                    assertThat(data).isEmpty();
                })
                .verifyComplete();
    }
}
