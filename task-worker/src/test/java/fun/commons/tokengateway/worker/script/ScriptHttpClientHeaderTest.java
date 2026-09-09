package fun.commons.tokengateway.worker.script;

import fun.commons.tokengateway.worker.config.WorkerProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ScriptHttpClient 请求头语义 (issue #15): 脚本显式 Content-Type 必须覆盖默认值,
 * 不得追加成重复头 (严格上游如 DashScope 以 400 拒收 "application/json,application/json").
 */
@DisplayName("ScriptHttpClient 请求头覆盖语义")
class ScriptHttpClientHeaderTest {

    private MockWebServer upstream;
    private ScriptHttpClient http;

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
        WorkerProperties props = new WorkerProperties();
        props.setEgressAllowlist(List.of(upstream.url("/").toString().replaceAll("/$", "")));
        http = new ScriptHttpClient(WebClient.builder(), props);
    }

    @AfterEach
    void tearDown() throws Exception {
        upstream.shutdown();
    }

    @Test
    @DisplayName("post 显式 Content-Type 覆盖默认 application/json, 单头无重复")
    void explicitContentTypeOverrides() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"));

        http.post(upstream.url("/tts"),
                Map.of("Authorization", "Bearer sk-x", "Content-Type", "text/plain"),
                Map.of("model", "qwen-tts"));

        RecordedRequest recorded = upstream.takeRequest();
        List<String> values = recorded.getHeaders().values("Content-Type");
        assertThat(values).hasSize(1);
        assertThat(values.get(0)).isEqualTo("text/plain");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer sk-x");
    }

    @Test
    @DisplayName("post 未显式 Content-Type 时保留默认 application/json")
    void defaultContentTypeWhenAbsent() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"));

        http.post(upstream.url("/chat"), Map.of("Authorization", "Bearer sk-x"),
                Map.of("prompt", "hi"));

        RecordedRequest recorded = upstream.takeRequest();
        List<String> values = recorded.getHeaders().values("Content-Type");
        assertThat(values).hasSize(1);
        assertThat(values.get(0)).isEqualTo("application/json");
    }
}
