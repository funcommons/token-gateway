package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.contract.DistributeVO;
import fun.commons.tokengateway.exception.RelayException;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenRouteClient (G2/G5): MockWebServer 假 token-route, 验证 resolve 契约
 * (snake_case 反序列化 + data_json 契约字段映射) 与 report 三态 (fire-and-forget).
 */
@DisplayName("TokenRouteClient resolve/report")
class TokenRouteClientTest {

    private MockWebServer server;
    private TokenRouteClient client;
    private TokenGatewayProperties spi;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        GatewayProperties legacy = new GatewayProperties();
        legacy.setUrl(server.url("/").toString().replaceAll("/$", ""));
        legacy.setTimeout(Duration.ofSeconds(2));
        spi = new TokenGatewayProperties();
        spi.getRoute().setTableId("tbl-llm-1");
        client = new TokenRouteClient(WebClient.builder(),
                new CapabilityEndpoints(spi, legacy), new RpcInternalAuth(legacy), spi);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("resolve: table_id/biz_params 契约 → data_json 映射 DistributeVO")
    void resolveMapsDataJson() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"entry_id\":\"e-1\",\"lease_id\":\"l-1\","
                        + "\"data_json\":{\"channelId\":\"tg-ch\",\"baseUrl\":\"http://tokengo:3000\","
                        + "\"apiKey\":\"sk-tg\",\"protocol\":\"openai\","
                        + "\"modelMapping\":{\"gpt-4o\":\"gpt-4o-tokengo\"}}}}"));

        StepVerifier.create(client.resolveFull("gpt-4o", "req-1", 100, 50, null))
                .assertNext(r -> {
                    assertThat(r.channel().getChannelId()).isEqualTo("tg-ch");
                    assertThat(r.channel().getBaseUrl()).isEqualTo("http://tokengo:3000");
                    assertThat(r.channel().getApiKey()).isEqualTo("sk-tg");
                    assertThat(r.channel().getProtocol()).isEqualTo("openai");
                    assertThat(r.channel().getModelMapping()).containsEntry("gpt-4o", "gpt-4o-tokengo");
                    assertThat(r.entryId()).isEqualTo("e-1");
                    assertThat(r.leaseId()).isEqualTo("l-1");
                })
                .verifyComplete();

        var recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();
        assertThat(recorded.getPath()).isEqualTo("/v1/resolve");
        assertThat(body)
                .contains("\"table_id\":\"tbl-llm-1\"")
                .contains("\"session_id\":\"req-1\"")
                .contains("\"model\":\"gpt-4o\"");
    }

    @Test
    @DisplayName("resolve 空结果 (TABLE_EMPTY) → RelayException 502")
    void resolveEmptyFails() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"reason\":\"TABLE_EMPTY\"}}"));

        StepVerifier.create(client.resolve("gpt-4o", null, 0, 0, null))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(RelayException.class)
                        .hasMessageContaining("TABLE_EMPTY"))
                .verify();
    }

    @Test
    @DisplayName("report 三态: SUCCESS 载荷契约; 失败 fire-and-forget 不抛")
    void reportFireAndForget() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"data\":{\"accepted\":1,\"rejected\":[]}}"));
        // 无响应 (连接失败) → 也不抛
        server.enqueue(new MockResponse().setResponseCode(500));

        StepVerifier.create(client.report("e-1", "l-1", TokenRouteClient.ReportResult.SUCCESS, 1, 42))
                .verifyComplete();
        StepVerifier.create(client.report("e-1", "l-1", TokenRouteClient.ReportResult.DISABLE_FAIL, 1, 0))
                .verifyComplete();

        var first = server.takeRequest();
        assertThat(first.getPath()).isEqualTo("/v1/report");
        assertThat(first.getBody().readUtf8())
                .contains("\"entry_id\":\"e-1\"")
                .contains("\"lease_id\":\"l-1\"")
                .contains("\"result\":\"SUCCESS\"")
                .contains("\"latency_ms\":42");
    }

    @Test
    @DisplayName("AdapterSelector: 非法 adapter fail-fast; tokengo/openapi 走 token-route")
    void adapterSelectorValidation() {
        TokenGatewayProperties props = new TokenGatewayProperties();
        AdapterSelector selector = new AdapterSelector(props);

        props.setAdapter("bogus");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, selector::validate);

        props.setAdapter("tokengo");
        selector.validate();
        assertThat(selector.routeViaTokenRoute()).isTrue();

        props.setAdapter("openapi");
        assertThat(selector.routeViaTokenRoute()).isTrue();

        props.setAdapter("mmagix");
        selector.validate();
        assertThat(selector.routeViaTokenRoute()).isFalse();

        props.setAdapter("custom:myAdapter");
        selector.validate();
        assertThat(selector.routeViaTokenRoute()).isFalse();

        // DistributeVO 默认语义 (import 保留检查)
        assertThat(new DistributeVO()).isNotNull();
    }
}
