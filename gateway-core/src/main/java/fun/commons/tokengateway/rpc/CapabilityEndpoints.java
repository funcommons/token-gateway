package fun.commons.tokengateway.rpc;

import fun.commons.tokengateway.config.GatewayProperties;
import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.EndpointConfig;
import fun.commons.tokengateway.spi.config.TaskBillingFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 能力面寻址 (G1 SPI 装配收尾): 按能力面取 {@code token-gateway.<face>} 配置.
 *
 * <p><b>兼容窗口</b> (issue #2 护栏): 某能力面 url 未配置 (null/空白) 时回退
 * 平移态 {@code gateway.backend.*} (同 url/timeout, 鉴权按 internal-token 有无
 * 映射 jwt/none) —— 存量部署零配置迁移; 全部面显式配置后可删 gateway.backend.*.
 * 第 8 面 {@link #taskBilling()} (issue #31) 逐字段缺省回退 {@link #billing()}.
 *
 * <p>寻址方 (rpc/* 各 Http*Api) 只认本类, 不直接感知两代配置键.
 */
@Component
@RequiredArgsConstructor
public class CapabilityEndpoints {

    private final TokenGatewayProperties spi;
    private final GatewayProperties legacy;

    /** 路由/分发面 (distribute, LLM 面 chat 渠道端点). */
    public EndpointConfig route() {
        EndpointConfig endpoint = resolve(spi.getRoute().getUrl(), spi.getRoute().getTimeout(),
                spi.getRoute().getAuth(), keyOf(spi.getRoute()));
        endpoint.setPath(spi.getRoute().getDistributePath());
        return endpoint;
    }

    /**
     * 任务面路由/分发端点 (#12 work 域): url/auth/timeout 复用 route 面,
     * path 取 {@code token-gateway.task.distribute-path} (默认 work-channels 端点).
     *
     * <p>回归 2026-09-21-04 BL11 P1-5: 两 face 分发端点必须分离 — 单键共用曾使
     * LLM face=all 部署的本地渠道路由误打 work-channels 端点, 宿主按 workId 解析
     * LLM 形状请求 (无 workId) 抛 {@code Long.parseLong(null)} → 502/10004.
     */
    public EndpointConfig taskRoute() {
        EndpointConfig endpoint = route();
        endpoint.setPath(spi.getTask().getDistributePath());
        return endpoint;
    }

    /** 凭证校验面. */
    public EndpointConfig tokenValidate() {
        return resolve(spi.getTokenValidate().getUrl(), spi.getTokenValidate().getTimeout(),
                spi.getTokenValidate().getAuth(), keyOf(spi.getTokenValidate()));
    }

    /** 计费面 (预扣/结算/退款; path=三端点路径前缀, issue #14). */
    public EndpointConfig billing() {
        EndpointConfig endpoint = resolve(spi.getBilling().getUrl(), spi.getBilling().getTimeout(),
                spi.getBilling().getAuth(), keyOf(spi.getBilling()));
        endpoint.setPath(spi.getBilling().getPathPrefix());
        return endpoint;
    }

    /**
     * 任务计费面 (issue #31): {@code token-gateway.task.billing.*}, 第 8 面.
     *
     * <p><b>逐字段缺省回退</b> {@link #billing()} 通用值 (url/auth/凭证/path-prefix/
     * timeout 任一未配即取 billing 面对应值, billing 面自身仍有 backend.* 平移回退) ——
     * path-prefix 回退即存量全局前缀, 存量 #12 部署把全局前缀指到 task 路径时行为不变.
     *
     * <p>鉴权: task 面独立配 key/jwt-secret/internal-token (internal-token 形态 auth
     * 未显式配时映射 jwt, 同 backend.* 平移语义); 凭证与 auth 均未配 → 整体取 billing 面
     * (EndpointConfig.auth 缺省 NONE 无法与显式 NONE 区分, 同 {@link #resolve} 既有局限).
     */
    public EndpointConfig taskBilling() {
        TaskBillingFaceConfig taskBilling = spi.getTask().getBilling();
        EndpointConfig base = billing();
        EndpointConfig merged = new EndpointConfig();
        merged.setUrl(firstNonBlank(taskBilling.getUrl(), base.getUrl()));
        merged.setTimeout(taskBilling.getTimeout() != null
                ? taskBilling.getTimeout() : base.getTimeout());
        merged.setPath(firstNonBlank(taskBilling.getPathPrefix(), base.getPath()));
        String credential = firstNonBlank(taskBilling.getKey(), taskBilling.getJwtSecret(),
                taskBilling.getInternalToken());
        if (credential != null) {
            // task 面独立凭证: 显式 auth 优先; 否则凭证形态缺省映射 jwt (internal-token 形态)
            merged.setAuth(taskBilling.getAuth() != null && taskBilling.getAuth() != AuthType.NONE
                    ? taskBilling.getAuth() : AuthType.JWT);
            merged.setKey(credential);
            merged.setJwtSecret(credential);
        } else {
            merged.setAuth(base.getAuth());
            merged.setKey(base.getKey());
            merged.setJwtSecret(base.getJwtSecret());
        }
        return merged;
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

    /** 取首个非 null/非空白值 (issue #31 逐字段回退用). */
    private static String firstNonBlank(String... values) {
        for (String s : values) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return null;
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
