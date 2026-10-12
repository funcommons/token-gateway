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
 * <p>发现新 taskType → {@code POST /api/v1/admin/types} 注册, 新模型上线控制面建脚本后
 * worker 下一 tick 自动可拉单, 零配置变更。<b>只补缺不覆盖</b>: 先 GET /admin/types 取
 * 既有类型集, 已存在的跳过 (saveTaskTypeConfig 的 upsert 会用默认参数覆盖运营调优值,
 * 故本组件绝不 upsert 既有类型)。
 *
 * <p>鉴权 = bearer-only (admin 面不要求 HMAC 四头), 且必须用<b>专用平台账号</b>
 * ({@code platformClientId/Secret} → {@link LotaskAuthSigner#platformBearer()}):
 * framework4j 单租户单会话互踢模型下共用租户账号会互踢 10205。
 *
 * <p>节律: once-per-process {@code ensured} 集, 成功才驻留; 失败移除下 tick 重试;
 * 401 自愈清平台缓存重登录。凭据未配齐时 {@link #enabled()}=false 全程 no-op
 * (缺省静默, 老部署与未建平台账号的环境零影响)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotaskTypeRegistrar {

    private final WebClient.Builder webClientBuilder;
    private final TokenGatewayProperties props;
    private final LotaskAuthSigner authSigner;

    /** once-per-process 已处理集 (成功驻留; 失败移除, 下 tick 重试). */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    /** lotask 既有类型集 (进程内一次拉取后缓存; volatile 读多写少). */
    private volatile Set<String> knownTypes;

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
     * 已存在的类型跳过 (只补缺), 清单拉取失败按失败重试处理 (宁缺勿滥 — 不经判定就 upsert
     * 会覆盖运营调参).
     */
    public void ensureRegistered(String taskType) {
        if (taskType == null || taskType.isBlank() || !enabled() || !ensured.add(taskType)) {
            return;
        }
        knownTypes()
                .flatMap(existing -> {
                    if (existing.contains(taskType)) {
                        log.info("[LotaskTypeRegistrar] 类型已存在, 跳过 (不覆盖运营调参): {}",
                                taskType);
                        return Mono.<Void>empty();
                    }
                    return doRegister(taskType);
                })
                .doOnSuccess(v -> log.info("[LotaskTypeRegistrar] 类型注册就绪: {}", taskType))
                .onErrorResume(e -> {
                    ensured.remove(taskType);
                    log.warn("[LotaskTypeRegistrar] 类型注册失败 (下 tick 重试): {}, err={}",
                            taskType, e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /** lotask 既有类型集 (GET /api/v1/admin/types, 进程内缓存一次). */
    private Mono<Set<String>> knownTypes() {
        Set<String> cached = knownTypes;
        if (cached != null) {
            return Mono.just(cached);
        }
        LotaskFaceConfig cfg = props.getTask().getLotask();
        String path = "/api/v1/admin/types";
        return authSigner.platformBearer()
                .flatMap(token -> webClientBuilder.build().get()
                        .uri(cfg.getUrl() + path)
                        .header("Authorization", "Bearer " + token)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(cfg.getReadTimeout()))
                .map(json -> {
                    JSONObject env = JSON.parseObject(json);
                    if (env.getIntValue("code") != 0 || env.getJSONArray("data") == null) {
                        throw new IllegalStateException(
                                "lotask admin 类型清单响应无效: " + env.getString("message"));
                    }
                    Set<String> keys = new java.util.HashSet<>();
                    env.getJSONArray("data").forEach(o -> {
                        JSONObject t = (JSONObject) o;
                        if (t.getString("typeKey") != null) {
                            keys.add(t.getString("typeKey"));
                        }
                    });
                    knownTypes = Set.copyOf(keys);
                    return knownTypes;
                })
                .onErrorResume(this::is401,
                        e -> authSigner.invalidatePlatform().then(Mono.error(e)));
    }

    /** upsert 单类型 (包级可见供测试; 仅对确认缺失的类型调用). */
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
