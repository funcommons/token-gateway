package fun.commons.tokengateway.task.lotask;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fun.commons.tokengateway.spi.config.AuthType;
import fun.commons.tokengateway.spi.config.LotaskFaceConfig;
import fun.commons.tokengateway.spi.config.TokenGatewayProperties;
import fun.commons.tokengateway.thmp.ThmpSignature;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * lotask4j 对接鉴权 (《06》§3.1: jwt 登录式 + 写操作 HMAC 四头).
 *
 * <p><b>bearer 获取 (V4+ 实测修订)</b>: 平台 /api/v1/auth/token (client_credentials,
 * client_id=租户名, client_secret=tenant_secret) 签发带会话指纹 (hash/jti) 的 HS256 JWT,
 * 自铸最小 JWT 不被 framework4j accesstoken 拦截器接受 (401) —— 故 auth=jwt 走
 * <i>登录 + 共享缓存 + exp 前置续期</i>. 平台会话策略为<b>单租户单会话</b> (新 token 踢旧),
 * 网关与 Worker 必须经 {@link LotaskTokenStore} 共享同一 bearer, 否则互踢
 * (「账号已在别处登录」); 登录失败 30s 冷却防打爆端点. 令牌被平台撤销时
 * (reset-secret/轮换) 由 client 侧 401 自愈调 {@link #invalidate()} 清缓存重登录.
 *
 * <p>写操作叠加 HMAC 四头 (X-Access-Key/Timestamp/Nonce/Signature), stringToSign 五段式与
 * framework4j-signature 一致 (复用 {@link ThmpSignature}, 密码学原语不重复建设).
 * 凭证均环境变量注入, 不落日志.
 */
@Component
@RequiredArgsConstructor
public class LotaskAuthSigner {

    /** jwt 有效期 (短期现铸; 仅 auth=jwt 但未配 tenant-name 的老平台兜底路径使用). */
    private static final Duration JWT_TTL = Duration.ofHours(1);

    /** 到期前置续期窗口. */
    private static final Duration RENEW_AHEAD = Duration.ofMinutes(5);

    /** 登录单飞锁 TTL (登录 RPC 秒级完成, 10s 足够且防持有者崩溃死锁). */
    private static final Duration LOGIN_LOCK_TTL = Duration.ofSeconds(10);

    /** 未获锁方等待回写 token 的轮询参数 (300ms × 10 = 3s, 超时兜底自登录). */
    private static final Duration AWAIT_STEP = Duration.ofMillis(300);
    private static final int AWAIT_ATTEMPTS = 10;

    /** 登录 RPC 超时 (独立于业务读超时). */
    private static final Duration LOGIN_TIMEOUT = Duration.ofSeconds(5);

    /** 登录失败冷却 (防每请求重试打爆登录端点). */
    private static final long LOGIN_BACKOFF_MS = 30_000;

    /** exp 解析失败时的兜底缓存时长. */
    private static final Duration FALLBACK_TTL = Duration.ofMinutes(50);

    private final TokenGatewayProperties props;

    private final WebClient.Builder webClientBuilder;

    private final LotaskTokenStore tokenStore;

    /** 登录失败冷却截止 (epoch ms). */
    private volatile long loginBackoffUntilMs;

    /** 本进程最近一次使用的 bearer (401 时判断被拒 token 是否仍是共享缓存的当前值). */
    private final AtomicReference<String> lastUsed = new AtomicReference<>();

    /** 平台身份最近一次使用的 bearer (invalidatePlatform 自愈比对用). */
    private final AtomicReference<String> platformLastUsed = new AtomicReference<>();

    private LotaskFaceConfig cfg() {
        return props.getTask().getLotask();
    }

    /** 共享缓存键 (按登录主体隔离; 未配 tenant-name 时唯一兜底键). */
    private String storeKey() {
        String name = cfg().getTenantName();
        return "tgw:lotask:tok:" + (name == null || name.isBlank() ? "default" : name);
    }

    private boolean fresh(LotaskTokenStore.CachedToken t) {
        return t != null
                && System.currentTimeMillis() < t.expireAtMs() - RENEW_AHEAD.toMillis();
    }

    /**
     * 平台 bearer (Mono 链内使用; 共享缓存有效直接复用, 否则经登录单飞锁刷新).
     * 登录端点/凭证未配置时回退自铸 JWT (老平台兜底, 见 {@link #hs256Jwt}).
     * 单飞: 拿锁进程登录回写, 其余进程等回写后直接复用 —— 单租户单会话互斥下,
     * 两进程同时登录必然互踢 (「账号已在别处登录」), 单飞锁是消除乒乓的关键.
     */
    public Mono<String> bearer() {
        String key = storeKey();
        return tokenStore.get(key)
                .filter(this::fresh)
                .switchIfEmpty(Mono.defer(() -> acquireAndSupply(key)))
                .map(LotaskTokenStore.CachedToken::token)
                .doOnNext(lastUsed::set);
    }

    /** 单飞登录: 抢锁 → 双检 → 登录回写 → 解锁; 未抢到锁则轮询等待回写. */
    private Mono<LotaskTokenStore.CachedToken> acquireAndSupply(String key) {
        return tokenStore.tryLock(lockKey(key), LOGIN_LOCK_TTL)
                .flatMap(locked -> {
                    if (!locked) {
                        return awaitFreshToken(key, AWAIT_ATTEMPTS);
                    }
                    return tokenStore.get(key)
                            .filter(this::fresh)
                            .switchIfEmpty(Mono.defer(() -> supply()
                                    .flatMap(t -> tokenStore.put(key, t).thenReturn(t))))
                            .doFinally(sig -> tokenStore.unlock(lockKey(key)).subscribe());
                });
    }

    /** 未获锁方: 轮询等对方回写; 超次数兜底自登录 (对方崩溃/锁异常时仍可推进). */
    private Mono<LotaskTokenStore.CachedToken> awaitFreshToken(String key, int left) {
        return awaitFreshToken(key, left, this::supply);
    }

    /** 泛化版 (平台第二身份复用; 登录函数由调用方给足身份参数). */
    private Mono<LotaskTokenStore.CachedToken> awaitFreshToken(String key, int left,
            java.util.function.Supplier<Mono<LotaskTokenStore.CachedToken>> loginFn) {
        if (left <= 0) {
            return loginFn.get().flatMap(t -> tokenStore.put(key, t).thenReturn(t));
        }
        return Mono.delay(AWAIT_STEP)
                .then(tokenStore.get(key).filter(this::fresh))
                .switchIfEmpty(Mono.defer(() -> awaitFreshToken(key, left - 1, loginFn)));
    }

    private String lockKey(String tokenKey) {
        return tokenKey + ":login-lock";
    }

    /** 签发新令牌: 登录就绪 → client_credentials 登录; 否则自铸兜底. */
    private Mono<LotaskTokenStore.CachedToken> supply() {
        LotaskFaceConfig cfg = cfg();
        boolean loginReady = cfg.getUrl() != null && !cfg.getUrl().isBlank()
                && cfg.getTenantName() != null && !cfg.getTenantName().isBlank()
                && cfg.getJwtSecret() != null && !cfg.getJwtSecret().isBlank();
        if (!loginReady) {
            long exp = System.currentTimeMillis() + JWT_TTL.toMillis();
            return Mono.fromCallable(() -> new LotaskTokenStore.CachedToken(
                    hs256Jwt(cfg.getJwtSecret(), "token-gateway", JWT_TTL), exp));
        }
        if (System.currentTimeMillis() < loginBackoffUntilMs) {
            return Mono.error(new IllegalStateException("lotask login 冷却期 (上次登录失败)"));
        }
        return doLogin(cfg)
                .doOnError(e -> loginBackoffUntilMs = System.currentTimeMillis() + LOGIN_BACKOFF_MS);
    }

    /** client_credentials 登录 → access_token + exp 解析. */
    private Mono<LotaskTokenStore.CachedToken> doLogin(LotaskFaceConfig cfg) {
        return doLogin(cfg.getUrl(), cfg.getTenantName(), cfg.getJwtSecret());
    }

    /** 登录原语 (租户/平台双身份复用; 凭据由调用方给足). */
    private Mono<LotaskTokenStore.CachedToken> doLogin(String url, String clientId,
            String clientSecret) {
        String form = "grant_type=client_credentials"
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8);
        return webClientBuilder.build().post()
                .uri(url + "/api/v1/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(LOGIN_TIMEOUT)
                .map(json -> {
                    JSONObject env = JSON.parseObject(json);
                    JSONObject data = env.getJSONObject("data");
                    String token = data == null ? null : data.getString("access_token");
                    if (env.getIntValue("code") != 0 || token == null || token.isBlank()) {
                        throw new IllegalStateException(
                                "lotask login 失败: " + env.getString("message"));
                    }
                    return new LotaskTokenStore.CachedToken(token, parseExpMs(token));
                });
    }

    /**
     * 401 自愈: 仅当共享缓存里仍是<b>本进程被拒的那个 token</b> 时才清除并触发重登录.
     * 缓存里若已是其他进程刚登录的新 token (平台 bearer 对所有持有者有效), 保留复用 ——
     * 盲清会误删新 token 造成两进程登录乒乓.
     */
    public Mono<Void> invalidate() {
        String key = storeKey();
        String rejected = lastUsed.get();
        return tokenStore.get(key)
                .filter(t -> rejected != null && rejected.equals(t.token()))
                .flatMap(t -> tokenStore.clear(key))
                .then();
    }

    /**
     * 平台第二身份 bearer (admin API 专用, worker 零配置自动注册 2026-10).
     * <p>framework4j 单租户单会话互踢模型下, admin 面复用租户账号会互踢 (10205) ——
     * 必须<b>专用平台账号</b>, storeKey 独立 (platform-&lt;clientId&gt;) 与租户身份互不影响.
     * 凭据未配置即 error (调用方 {@code enabled()} 已先挡, 此处兜底); 登录复用
     * {@link #awaitFreshToken} 单飞机制 (键隔离).
     */
    public Mono<String> platformBearer() {
        LotaskFaceConfig cfg = cfg();
        if (cfg.getPlatformClientId() == null || cfg.getPlatformClientId().isBlank()
                || cfg.getPlatformClientSecret() == null || cfg.getPlatformClientSecret().isBlank()) {
            return Mono.error(new IllegalStateException(
                    "lotask platform client 未配置 (autoRegister 需专用平台账号)"));
        }
        String key = "tgw:lotask:tok:platform-" + cfg.getPlatformClientId();
        return tokenStore.get(key)
                .filter(this::fresh)
                .switchIfEmpty(Mono.defer(() -> acquirePlatformAndSupply(key, cfg)))
                .map(LotaskTokenStore.CachedToken::token)
                .doOnNext(platformLastUsed::set);
    }

    /** 平台身份单飞登录 (与租户路径同构: 抢锁 → 双检 → 登录回写; 键隔离). */
    private Mono<LotaskTokenStore.CachedToken> acquirePlatformAndSupply(String key,
            LotaskFaceConfig cfg) {
        return tokenStore.tryLock(lockKey(key), LOGIN_LOCK_TTL)
                .flatMap(locked -> {
                    if (!locked) {
                        return awaitFreshToken(key, AWAIT_ATTEMPTS,
                                () -> doLogin(cfg.getUrl(), cfg.getPlatformClientId(),
                                        cfg.getPlatformClientSecret()));
                    }
                    return tokenStore.get(key)
                            .filter(this::fresh)
                            .switchIfEmpty(Mono.defer(() -> doLogin(cfg.getUrl(),
                                            cfg.getPlatformClientId(), cfg.getPlatformClientSecret())
                                    .flatMap(t -> tokenStore.put(key, t).thenReturn(t))))
                            .doFinally(sig -> tokenStore.unlock(lockKey(key)).subscribe());
                });
    }

    /** 平台身份 401 自愈 (语义同 {@link #invalidate}, 作用域 = 平台 storeKey). */
    public Mono<Void> invalidatePlatform() {
        LotaskFaceConfig cfg = cfg();
        if (cfg.getPlatformClientId() == null || cfg.getPlatformClientId().isBlank()) {
            return Mono.empty();
        }
        String key = "tgw:lotask:tok:platform-" + cfg.getPlatformClientId();
        String rejected = platformLastUsed.get();
        return tokenStore.get(key)
                .filter(t -> rejected != null && rejected.equals(t.token()))
                .flatMap(t -> tokenStore.clear(key))
                .then();
    }

    /**
     * 解析 JWT payload 的 exp (毫秒 epoch); 非标准 JWT/解析失败 → 50min 兜底 (偏保守).
     */
    static long parseExpMs(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return System.currentTimeMillis() + FALLBACK_TTL.toMillis();
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JSONObject claims = JSON.parseObject(payload);
            long exp = claims.getLongValue("exp");
            return exp > 0 ? exp : System.currentTimeMillis() + FALLBACK_TTL.toMillis();
        } catch (Exception e) {
            return System.currentTimeMillis() + FALLBACK_TTL.toMillis();
        }
    }

    /**
     * 统一鉴权入口 (写请求): JWT → 登录缓存 bearer 后附加; KEY/NONE → 旧同步路径.
     * 与 {@link #attachSignature} 正交 (签名不依赖 token, 在 authorize 之后叠加).
     */
    public Mono<WebClient.RequestBodySpec> authorize(WebClient.RequestBodySpec spec) {
        if (cfg().getAuth() == AuthType.JWT) {
            return bearer().map(token -> {
                spec.header("Authorization", "Bearer " + token);
                return spec;
            });
        }
        attachAuth(spec);
        return Mono.just(spec);
    }

    /**
     * 统一鉴权入口 (读请求, 通配泛型): 语义同 {@link #authorize(WebClient.RequestBodySpec)}.
     */
    public Mono<WebClient.RequestHeadersSpec<?>> authorize(WebClient.RequestHeadersSpec<?> spec) {
        if (cfg().getAuth() == AuthType.JWT) {
            return bearer().map(token -> {
                spec.header("Authorization", "Bearer " + token);
                return (WebClient.RequestHeadersSpec<?>) spec;
            });
        }
        attachAuth(spec);
        return Mono.just(spec);
    }


    /**
     * 同步附加主鉴权头 (KEY/NONE 路径; auth=jwt 走 {@link #authorize} 登录缓存, 不经此).
     * key → X-Api-Key; none → 不发 (仅 localhost).
     */
    public void attachAuth(WebClient.RequestHeadersSpec<?> spec) {
        LotaskFaceConfig cfg = cfg();
        switch (cfg.getAuth()) {
            case JWT -> {
                if (cfg.getJwtSecret() != null && !cfg.getJwtSecret().isBlank()) {
                    spec.header("Authorization",
                            "Bearer " + hs256Jwt(cfg.getJwtSecret(), "token-gateway", JWT_TTL));
                }
            }
            case KEY -> {
                if (cfg.getKey() != null && !cfg.getKey().isBlank()) {
                    spec.header("X-Api-Key", cfg.getKey());
                }
            }
            case NONE -> {
                // 仅 localhost/sidecar 同机隔离 (CapabilityValidator 启动告警兜底)
            }
        }
    }

    /**
     * 写操作 (submit/cancel) 附加 HMAC 四头; access-key/sign-key 未配置时不签 (平台侧按应用配置决定是否强制).
     *
     * @param path    不含 query 的请求路径 (如 /api/v1/client/tasks/submit)
     * @param rawBody 请求体原始字节 (签名与实际发送必须同源同字节)
     */
    public void attachSignature(WebClient.RequestHeadersSpec<?> spec, String method, String path,
                                byte[] rawBody) {
        LotaskFaceConfig cfg = cfg();
        if (cfg.getAccessKey() == null || cfg.getAccessKey().isBlank()
                || cfg.getSignKey() == null || cfg.getSignKey().isBlank()) {
            return;
        }
        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String toSign = ThmpSignature.buildStringToSign(method, path, timestamp, nonce,
                ThmpSignature.md5Hex(rawBody));
        spec.header("X-Access-Key", cfg.getAccessKey());
        spec.header("X-Timestamp", timestamp);
        spec.header("X-Nonce", nonce);
        spec.header("X-Signature", ThmpSignature.sign(cfg.getSignKey(), toSign));
    }

    /**
     * 最小 HS256 JWT (无 jjwt 依赖; header/payload 固定形状, sub 固定 token-gateway).
     */
    static String hs256Jwt(String secret, String subject, Duration ttl) {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = base64Url(("{\"sub\":\"" + subject + "\",\"iat\":" + now
                + ",\"exp\":" + (now + ttl.getSeconds()) + "}").getBytes(StandardCharsets.UTF_8));
        String content = header + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return content + "." + base64Url(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("lotask jwt 签发失败", e);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
