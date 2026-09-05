package fun.commons.tokengateway.relay;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.config.HealthReportProperties;
import fun.commons.tokengateway.contract.RecordFailureRequest;
import fun.commons.tokengateway.framework.ApiResponse;
import fun.commons.tokengateway.rpc.HttpChannelApi;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 测试辅助: ChannelHealthReporter 桩.
 *
 * <p>{@link #disabled()} 关闭态 (不发任何 HTTP, 避免占用 MockWebServer 应答队列);
 * {@link #recording(List)} 开启态但 RPC 桩化, 调用记录进 calls 列表 (格式
 * {@code success:<channelId>} / {@code failure:<channelId>:<errorCode>}), 供断言.
 */
public final class TestChannelHealthReporters {

    private TestChannelHealthReporters() {
    }

    public static ChannelHealthReporter disabled() {
        HealthReportProperties props = new HealthReportProperties();
        props.setEnabled(false);
        return new ChannelHealthReporter(null, props);
    }

    public static ChannelHealthReporter recording(List<String> calls) {
        HealthReportProperties props = new HealthReportProperties();
        props.setEnabled(true);
        GatewayProperties gatewayProps = new GatewayProperties();
        HttpChannelApi stub = new HttpChannelApi(WebClient.builder(), new fun.commons.tokengateway.rpc.CapabilityEndpoints(new fun.commons.tokengateway.spi.config.TokenGatewayProperties(), gatewayProps),
                new fun.commons.tokengateway.rpc.RpcInternalAuth(gatewayProps)) {
            @Override
            public Mono<ApiResponse<Void>> recordSuccess(String channelId) {
                calls.add("success:" + channelId);
                return Mono.just(ApiResponse.success());
            }

            @Override
            public Mono<ApiResponse<Void>> recordFailure(String channelId, RecordFailureRequest request) {
                calls.add("failure:" + channelId + ":" + request.getErrorCode());
                return Mono.just(ApiResponse.success());
            }
        };
        return new ChannelHealthReporter(stub, props);
    }
}
