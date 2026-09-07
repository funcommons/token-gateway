package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 协议形状适配器单选 (G2, 设计方案 §6.1: 全局单选, 一次部署一个协议形状).
 *
 * <p>职责: 启动期校验 {@code token-gateway.adapter} 合法值 (fail-fast, 对齐
 * face 非法值口径) + 声明 route 面的解析后端:
 * <ul>
 *   <li>mmagix | tokenhub | custom:* → Mmagix distribute (现状路径, 零行为变化)</li>
 *   <li>tokengo | openapi → token-route resolve/report (TokenRouteClient,
 *       一期 face=llm; TokenGo 为 new-api fork, OpenAI 兼容直通)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdapterSelector {

    /** route 面走 token-route 的适配器 (OpenAI 兼容直通族). */
    private static final Set<String> TOKEN_ROUTE_ADAPTERS = Set.of("tokengo", "openapi");

    private final TokenGatewayProperties spi;

    /** 启动期校验 (fail-fast, 对齐 face 非法值口径). */
    @jakarta.annotation.PostConstruct
    void init() {
        validate();
        logActive();
    }

    /** 合法值: mmagix | tokenhub | tokengo | openapi | custom:<spiName>. */
    public void validate() {
        String adapter = spi.getAdapter();
        if (adapter == null || adapter.isBlank()
                || Set.of("mmagix", "tokenhub", "tokengo", "openapi").contains(adapter)
                || adapter.startsWith("custom:")) {
            return;
        }
        throw new IllegalStateException("token-gateway.adapter 非法值: '" + adapter
                + "' (允许 mmagix | tokenhub | tokengo | openapi | custom:<spiName>)");
    }

    /**
     * route 面是否走 token-route (resolve/report).
     *
     * <p>②⑦ tableId 门控: 除适配器族判据外, 还要求 {@code token-gateway.route.table-id}
     * 非空白——未配置时 route 面静默回落 distribute (Mmagix 契约), 保证部署面可先起进程
     * (TokenGo 能力面 /api/v1/internal/channels/distribute 同契约), 配齐 table-id 后
     * 重启即切 token-route。
     */
    public boolean routeViaTokenRoute() {
        if (!TOKEN_ROUTE_ADAPTERS.contains(spi.getAdapter())) {
            return false;
        }
        String tableId = spi.getRoute() == null ? null : spi.getRoute().getTableId();
        return tableId != null && !tableId.isBlank();
    }

    /** 启动期声明 (装配后调用一次, 显式暴露当前协议形状). */
    public void logActive() {
        String tableId = spi.getRoute() == null ? null : spi.getRoute().getTableId();
        boolean viaTokenRoute = routeViaTokenRoute();
        log.info("[Adapter] 协议形状: {}, route 面后端: {}, table-id: {}", spi.getAdapter(),
                viaTokenRoute ? "token-route (resolve/report)" : "distribute (Mmagix 契约)",
                viaTokenRoute ? tableId : "(未配置)");
        if (!viaTokenRoute && TOKEN_ROUTE_ADAPTERS.contains(spi.getAdapter())) {
            log.warn("[Adapter] adapter={} 属 token-route 族但 token-gateway.route.table-id 未配置,"
                            + " route 面已回落 distribute; 配齐后重启生效", spi.getAdapter());
        }
    }
}
