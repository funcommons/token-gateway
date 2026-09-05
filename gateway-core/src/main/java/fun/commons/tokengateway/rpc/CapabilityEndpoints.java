package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 能力面寻址 (G1 SPI 装配收尾): 按能力面取 {@code token-gateway.<face>} 配置.
 *
 * <p><b>兼容窗口</b> (issue #2 护栏): 某能力面 url 未配置 (null/空白) 时回退
 * 平移态 {@code gateway.backend.*} (同 url/timeout, 鉴权按 internal-token 有无
 * 映射 jwt/none) —— 存量部署零配置迁移; 全部七面显式配置后可删 gateway.backend.*.
 *
 * <p>寻址方 (rpc/* 各 Http*Api) 只认本类, 不直接感知两代配置键.
 */
@Component
@RequiredArgsConstructor
public class CapabilityEndpoints {

    private final TokenGatewayProperties spi;
    private final GatewayProperties legacy;

    /** 路由/分发面 (distribute). */
    public EndpointConfig route() {
        return resolve(spi.getRoute().getUrl(), spi.getRoute().getTimeout(),
                spi.getRoute().getAuth(), keyOf(spi.getRoute()));
    }

    /** 凭证校验面. */
    public EndpointConfig tokenValidate() {
        return resolve(spi.getTokenValidate().getUrl(), spi.getTokenValidate().getTimeout(),
                spi.getTokenValidate().getAuth(), keyOf(spi.getTokenValidate()));
    }

    /** 计费面 (预扣/结算/退款). */
    public EndpointConfig billing() {
        return resolve(spi.getBilling().getUrl(), spi.getBilling().getTimeout(),
                spi.getBilling().getAuth(), keyOf(spi.getBilling()));
    }

    /** 内容审核面. */
    public EndpointConfig moderation() {
        return resolve(spi.getModeration().getUrl(), spi.getModeration().getTimeout(),
                spi.getModeration().getAuth(), keyOf(spi.getModeration()));
    }

    /** 日志投递面 (transport=rpc). */
    public EndpointConfig accessLog() {
        return resolve(spi.getAccessLog().getUrl(), spi.getAccessLog().getTimeout(),
                spi.getAccessLog().getAuth(), keyOf(spi.getAccessLog()));
    }

    /** 审计面. */
    public EndpointConfig audit() {
        return resolve(spi.getAudit().getUrl(), spi.getAudit().getTimeout(),
                spi.getAudit().getAuth(), keyOf(spi.getAudit()));
    }

    /** 模型目录面. */
    public EndpointConfig modelCatalog() {
        return resolve(spi.getModelCatalog().getUrl(), spi.getModelCatalog().getTimeout(),
                spi.getModelCatalog().getAuth(), keyOf(spi.getModelCatalog()));
    }

    private String keyOf(EndpointConfig cfg) {
        if (cfg.getKey() != null && !cfg.getKey().isBlank()) {
            return cfg.getKey();
        }
        return cfg.getJwtSecret();
    }

    /**
     * 兼容窗口: 面配置 url 为空 → 平移态回退 (同 url/timeout, jwt=internal-token 形态).
     */
    private EndpointConfig resolve(String faceUrl, java.time.Duration faceTimeout,
                                   fun.commons.tokengateway.spi.config.AuthType auth, String credential) {
        if (faceUrl != null && !faceUrl.isBlank()) {
            EndpointConfig cfg = new EndpointConfig();
            cfg.setUrl(faceUrl);
            cfg.setTimeout(faceTimeout != null ? faceTimeout : legacy.getTimeout());
            cfg.setAuth(auth);
            cfg.setKey(credential);
            cfg.setJwtSecret(credential);
            return cfg;
        }
        EndpointConfig fallback = new EndpointConfig();
        fallback.setUrl(legacy.getUrl());
        fallback.setTimeout(legacy.getTimeout());
        String token = legacy.getInternalToken();
        boolean hasToken = token != null && !token.isBlank();
        fallback.setAuth(hasToken
                ? fun.commons.tokengateway.spi.config.AuthType.JWT
                : fun.commons.tokengateway.spi.config.AuthType.NONE);
        fallback.setJwtSecret(token);
        fallback.setKey(token);
        return fallback;
    }
}
