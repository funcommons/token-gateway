package fun.commons.tokengateway.worker.lotask;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.task.lotask.LotaskAuthSigner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * lotask 任务类型自动注册 (worker 零配置改造 M3, 2026-10).
 *
 * <p>发现新 taskType → {@code POST /api/v1/admin/types} 幂等 upsert ("新增或更新任务
 * 类型配置"), 新模型上线控制面建脚本后 worker 下一 tick 自动可拉单, 零配置变更.
 *
 * <p>鉴权 = bearer-only (admin 面不要求 HMAC 四头), 且必须用<b>专用平台账号</b>
 * ({@code platformClientId/Secret} → {@link LotaskAuthSigner#platformBearer()}):
 * framework4j 单租户单会话互踢模型下共用租户账号会互踢 10205。
 *
 * <p>节律: once-per-process {@code ensured} 集, 成功才驻留; 失败移除下 tick 重试;
 * 401 自愈清平台缓存重登录. 凭据未配齐时 {@link #enabled()}=false 全程 no-op
 * (缺省静默, 老部署与未建平台账号的环境零影响).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotaskTypeRegistrar {

    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;
    private final LotaskAuthSigner authSigner;

    /** once-per-process 已注册集 (成功驻留; 失败移除, 下 tick 重试). */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    /**
     * admin upsert 是否就绪 (autoRegister 开启 + 专用平台凭据 + tenantId 齐备).
     */
    public boolean enabled() {
        LotaskFaceConfig cfg = props.getTask().getLotask();
        return cfg.isAutoRegister()
                && notBlank(cfg.getPlatformClientId())
                && notBlank(cfg.getPlatformClientSecret())
                && cfg.getPlatformTenantId() != null;
    }

    /**
     * 发现新类型即注册 (fire-and-forget: 异步不阻塞拉单 tick; 失败移除 ensured 下 tick 重试).
     */
    public void ensureRegistered(String taskType) {
        if (taskType == null || taskType.isBlank() || !enabled() || !ensured.add(taskType)) {
            return;
        }
        doRegister(taskType).subscribe(
                v -> log.info("[LotaskTypeRegistrar] 类型已注册: {}", taskType),
                e -> {
                    ensured.remove(taskType);
                    log.warn("[LotaskTypeRegistrar] 类型注册失败 (下 tick 重试): {}, err={}",
                            taskType, e.getMessage());
                });
    }

    /** upsert 单类型 (包级可见供测试). */
    Mono<Void> doRegister(String taskType) {
        LotaskFaceConfig cfg = props.getTask().getLotask();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", cfg.getPlatformTenantId());
        body.put("typeKey", taskType);
        body.put("name", taskType);
        body.put("concurrencyLimit", cfg.getTypeConcurrencyLimit());
        body.put("timeoutSeconds", cfg.getTypeTimeoutSeconds());
        body.put("maxRetries", cfg.getTypeMaxRetries());
        body.put("isEnabled", true);
        byte[] raw = JSON.toJSONBytes(body);
        String path = "/api/v1/admin/types";
        return authSigner.platformBearer()
                .flatMap(token -> webClientBuilder.build().post()
                        .uri(cfg.getUrl() + path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(new String(raw, StandardCharsets.UTF_8))
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(cfg.getReadTimeout()))
                .flatMap(json -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0) {
                        return Mono.error(new IllegalStateException(
                                "lotask admin 拒绝: " + env.getString("message")));
                    }
                    return Mono.<Void>empty();
                })
                .onErrorResume(this::is401,
                        e -> authSigner.invalidatePlatform().then(Mono.error(e)));
    }

    private boolean is401(Throwable e) {
        return e instanceof WebClientResponseException w && w.getStatusCode().value() == 401;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
