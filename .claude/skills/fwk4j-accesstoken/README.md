# framework4j-accesstoken

> JWT + Redis 双重验证 Token SDK：access token（≤ 2h）+ refresh token family（≤ 30d 一次性 + 重用检测 + 全族撤销）。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | `AccessTokenGenerator`（access 签发 / 撤销 / 黑名单）/ `RefreshTokenService`（family + Lua 原子轮转 + 毒丸 + maxRotations）/ `TokenInterceptor` 路由 + `AccessToken/RefreshValidationStrategy` / `TokenContext` / `TokenKeyBuilder` / `TokenUtils`（HS256 + ThreadLocal Mac 缓存） |
| 配置前缀 | `framework4j.access-token.*`（kebab-case） |
| 必需依赖 | `framework4j-redis`、`spring-boot-starter-web`、`spring-boot-starter-data-redis`、`jackson-databind` |
| 可选依赖 | `jakarta.servlet-api`（provided） |
| 在 SDK 中的位置 | 安全层，依赖 `redis`，被消费者应用 Controller 用 `@RequiresToken` |

**核心原则**（mc-java-security 铁律 3）：access_token ≤ 2h，refresh_token ≤ 30d 且一次性；refresh 重用 → 全族撤销；maxRotations 上限防无限轮转。

## 2. 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-accesstoken</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- 自动引入 framework4j-redis -->
```

### 最小 application.yml

```yaml
spring:
  application:
    name: my-app  # 必填，用作 Redis key 前缀

framework4j:
  access-token:
    enabled: true
    secret-key: ${JWT_SECRET}  # 必填，≥ 32 字符（256 位），环境变量注入
    hash-salt: ${HASH_SALT}    # 必填
    redis-name: default        # 用 framework4j-redis 的哪个数据源
    path-patterns:
      - /v1/**
    exclude-path-patterns:
      - /v1/auth/login
      - /v1/auth/register
    policies:
      user:                    # token 类型 = "user"
        key: [uid]             # 互斥键字段（claims 中必含）
        expire-time: 7200      # 2h
        refresh-expire-time: 2592000  # 30d
        max-rotations: 20      # family 轮转上限
        auto-renew: false
        max-usage: null        # null = 不限次
```

### 最小代码示例

```java
// 登录端点：签发 access + refresh pair
@PostMapping("/v1/auth/login")
public ApiResponse<LoginVO> login(@RequestBody LoginRequest req) {
    UserDO user = userService.verify(req.username(), req.password());
    
    Map<String, Object> claims = new HashMap<>();
    claims.put("uid", String.valueOf(user.getId()));
    claims.put("type", "user");
    
    RefreshTokenService.TokenPair pair = refreshTokenService.generateTokenPair(claims);
    LoginVO vo = new LoginVO(pair.accessToken(), pair.refreshToken(), pair.familyId());
    return ApiResponse.success(vo, TraceContext.getTraceId());
}

// 受保护端点：@RequiresToken 自动校验
@GetMapping("/v1/users/me")
@RequiresToken("user")
public ApiResponse<UserVO> me() {
    String uid = TokenContext.getClaim("uid");
    return ApiResponse.success(userService.find(uid), TraceContext.getTraceId());
}

// 刷新端点：用 refresh 换新 pair
@PostMapping("/v1/auth/refresh")
@RequiresToken(value = "user", type = "refresh")
public ApiResponse<LoginVO> refresh() {
    String refreshToken = extractFromHeader();  // 消费者自取
    RefreshTokenService.TokenPair pair = refreshTokenService.refreshAccessToken(refreshToken);
    LoginVO vo = new LoginVO(pair.accessToken(), pair.refreshToken(), pair.familyId());
    return ApiResponse.success(vo, TraceContext.getTraceId());
}
```

## 3. 配置参考

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.access-token.enabled` | `boolean` | `false` | 是否启用（opt-in） |
| `framework4j.access-token.secret-key` | `String` | 必填 | HS256 签名密钥，≥ 32 字符，环境变量 |
| `framework4j.access-token.hash-salt` | `String` | 必填 | Redis key 哈希盐，环境变量 |
| `framework4j.access-token.expire-time` | `long` | `7200`（2h） | 全局默认 access TTL（秒） |
| `framework4j.access-token.redis-name` | `String` | `default` | 用 `framework4j-redis` 的哪个数据源 |
| `framework4j.access-token.path-patterns` | `List<String>` | `["/**"]` | 拦截器路径模式 |
| `framework4j.access-token.exclude-path-patterns` | `List<String>` | `[]` | 排除路径 |
| `framework4j.access-token.policies.<type>.key` | `List<String>` | 必填 | 互斥键字段名（claims 中必含） |
| `framework4j.access-token.policies.<type>.expire-time` | `Long` | 全局值 | access TTL（秒） |
| `framework4j.access-token.policies.<type>.max-usage` | `Integer` | `null` | 限次消费次数（null = 不限） |
| `framework4j.access-token.policies.<type>.activation-time-limit` | `Long` | `null` | 激活时间限制（秒，邀请码场景） |
| `framework4j.access-token.policies.<type>.auto-renew` | `Boolean` | `false` | 自动续期 |
| `framework4j.access-token.policies.<type>.renew-increment` | `Long` | `null` | 续期步长（秒） |
| `framework4j.access-token.policies.<type>.refresh-expire-time` | `Long` | `2592000`（30d） | refresh TTL（秒） |
| `framework4j.access-token.policies.<type>.max-rotations` | `Integer` | `20` | family 轮转上限 |

## 4. API 参考

### `@RequiresToken`（注解）

```java
@Target({METHOD, TYPE})
@Retention(RUNTIME)
public @interface RequiresToken {
    String value();                                    // token 业务类型，如 "user"
    String type() default "access";                    // "access" / "refresh"
    Class<? extends Exception> exception() default AuthException.class;
}
```

### `AccessTokenGenerator`

```java
public class AccessTokenGenerator {
    public static final String TYPE_ACCESS = "access";
    
    public String generateToken(String tokenType, Map<String, Object> claims);
    public void revokeToken(String token);
    public boolean isRevoked(String jti);
    public String buildRedisKey(String tokenType, String hash);
    public String getAppName();
    
    public record TokenPair(
        String accessToken,
        String refreshToken,
        String familyId,
        long accessExpiresInSeconds,
        long refreshExpiresInSeconds
    ) {}
}
```

### `RefreshTokenService`

```java
public class RefreshTokenService {
    public static final String TYPE_REFRESH = "refresh";
    
    public AccessTokenGenerator.TokenPair generateTokenPair(Map<String, Object> claims);
    public AccessTokenGenerator.TokenPair refreshAccessToken(String refreshTokenString);
    public void revokeFamily(String familyId);
}
```

**`refreshAccessToken` 错误码**：
- `10210 REFRESH_EXPIRED` — refresh 过期或 family 不存在
- `10211 REFRESH_INVALID` — 类型错 / 缺字段 / 被重用
- `10212 REFRESH_ROTATION_EXCEEDED` — 轮转超 maxRotations

### `TokenInterceptor`

`HandlerInterceptor`，仅做路由：
1. 提取 `Authorization: Bearer <token>`
2. `TokenUtils.parseToken` 验签 + 过期检查
3. 按 `annotation.type()` 分流到 `AccessTokenValidationStrategy` 或 `RefreshTokenValidationStrategy`

### `TokenContext`（ThreadLocal）

```java
public class TokenContext {
    public static void set(String tokenType, Map<String, Object> claims);
    public static ContextData getContext();
    public static String getTokenType();
    public static <T> T getClaim(String key);
    public static Map<String, Object> getClaims();
    public static void clear();
}
```

### `TokenKeyBuilder`

```java
public final class TokenKeyBuilder {
    public static String accessMetadata(String app, String type, String hash);     // {app}:accesstoken:{type}:{hash}
    public static String accessUsageStats(String redisKey);                       // {redisKey}:stats:usage
    public static String accessRevokedSet(String app);                            // access:revoked:{app}
    public static String refreshFamily(String app, String familyId);              // refresh:family:{app}:{familyId}
    public static String refreshRevokedPoison(String app, String familyId);       // refresh:revoked:{app}:{familyId}
}
```

### `TokenUtils`

```java
public class TokenUtils {
    public static String createToken(String type, String nonce, String keyHash,
                                     String iss, long iat, long exp, String jti, String secret);
    public static String createToken(String type, String nonce, String keyHash,
                                     String iss, long iat, long exp, String jti,
                                     String family, String secret);  // refresh 用
    public static Map<String, Object> parseToken(String token, String secret);
    public static String calculateKeyHash(Object keyValue, String salt);
}
```

内部用 `ThreadLocal<Mac>` 缓存 HMAC 实例（hot-path 优化）。

## 5. 示例

### 5.1 互斥登录（踢人下线）

```yaml
framework4j:
  access-token:
    policies:
      user:
        key: [uid]  # 同 uid 只能有一个有效 token
```

新登录会覆盖 Redis 中的旧 token nonce，旧 token 再请求时 nonce 不匹配 → `10205 账号已在别处登录`。

### 5.2 限次消费（邀请码）

```yaml
framework4j:
  access-token:
    policies:
      invite:
        key: [inviteCode]
        expire-time: 86400   # 24h
        max-usage: 5          # 最多用 5 次
```

每次请求 `Redis INCR {redisKey}:stats:usage`，超过 5 次 → `10201 令牌使用次数超限`。

### 5.3 Refresh token 一次性 + 重用检测

```java
@PostMapping("/v1/auth/refresh")
@RequiresToken(value = "user", type = "refresh")
public ApiResponse<LoginVO> refresh(@RequestBody RefreshRequest req) {
    // interceptor 已校验 refresh 有效 + 未 poison
    // 真正的轮转由 service 完成
    TokenPair pair = refreshTokenService.refreshAccessToken(req.refreshToken());
    return ApiResponse.success(new LoginVO(pair.accessToken(), pair.refreshToken(), pair.familyId()),
            TraceContext.getTraceId());
}
```

**重用检测流程**：
1. 第一次 `refreshAccessToken(oldRefresh)` → Lua 标记 `consumed=true`，返回新 pair
2. 攻击者复用 `oldRefresh` → Lua 看到 `consumed=true` → 抛 `REUSED`
3. Java 层捕获 `REUSED` → 写毒丸 `refresh:revoked:{app}:{familyId}=1` + `revokeFamily()`
4. family 中所有 jti 加入 `access:revoked:{app}` Set，删除 family Hash
5. 后续任何该 family 的 refresh 请求 → interceptor 检测到毒丸 → `10211`

### 5.4 主动注销

```java
@PostMapping("/v1/auth/logout")
@RequiresToken("user")
public ApiResponse<Void> logout() {
    String token = extractTokenFromHeader();
    generator.revokeToken(token);  // 删 Redis + jti 加黑名单
    return ApiResponse.success(null, TraceContext.getTraceId());
}
```

## 6. 错误码

| Code | 名称 | 触发场景 |
|---|---|---|
| `10200` | `UNAUTHORIZED` | 未提供 Authorization 头 / Claims 缺失 / TokenType 未定义 |
| `10201` | `TOKEN_EXPIRED` | Token 过期 / Redis 不存在 / 使用次数超限 / 会话达最大时长 |
| `10202` | `TOKEN_INVALID` | 签名验证失败 |
| `10205` | `KICKED_OUT` | nonce 不匹配（互斥登录） |
| `10207` | `TOKEN_FORMAT_ERROR` | 段数不对 / Base64 解析失败 / 缺 exp/iat |
| `10208` | `TOKEN_REVOKED` | jti 在黑名单 |
| `10210` | `REFRESH_EXPIRED` | refresh 过期 / family 不存在 / jti 不在 family 中 |
| `10211` | `REFRESH_INVALID` | refresh 类型错 / 缺 family/jti / 被重用 / family 已撤销 |
| `10212` | `REFRESH_ROTATION_EXCEEDED` | family 轮转超 maxRotations |
| `10300` | `FORBIDDEN` | token 类型与 `@RequiresToken.value()` 不匹配 |
| `10500` | `SYSTEM_BUSY` | Redis 存储失败 |

## 7. FAQ

**Q1：access token 和 refresh token 区别？**
A：access token 短命（≤ 2h），每次请求带，验签 + Redis 双重校验。refresh token 长命（≤ 30d）一次性，仅用于 `/v1/auth/refresh` 端点换新 access。两者用同一个 secret 签名，靠 `type` claim 区分。

**Q2：为什么 refresh token 要"一次性 + family"？**
A：一次性保证旧 refresh 被复用时能检测（`consumed=true`）。family 把同一次登录的所有 refresh 串成链，重用检测触发时撤销整族（防攻击者用已泄露的 refresh 继续轮转）。详见 mc-java-security §1。

**Q3：`maxRotations=20` 够用吗？**
A：30d TTL + 每次 access 过期才轮转（默认 2h），理论上 30d 内最多 360 次。但实际用户不会每 2h 准时刷新，20 次足够 95% 用户。达到上限强制重新登录是安全兜底。

**Q4：`TokenContext` 在异步线程怎么传递？**
A：`TokenContext` 基于 `ThreadLocal`，异步线程不自动传递。需要手动：

```java
ContextData data = TokenContext.getContext();
executor.submit(() -> {
    TokenContext.setContext(data);
    try {
        // 业务逻辑
    } finally {
        TokenContext.clear();
    }
});
```

**Q5：怎么自定义异常类型？**
A：`@RequiresToken(exception = MyAuthException.class)`。SDK 用反射优先调 `(int, String)` 构造，回退到 `(String)`，最后回退到无参。这样能保留精确错误码。

**Q6：拦截器路径怎么配置？**
A：`framework4j.access-token.path-patterns` + `exclude-path-patterns`。默认 `["/**"]`，由 `@RequiresToken` 注解决定是否真鉴权（无注解的接口放行）。建议排除登录 / 注册 / 公开接口。

## v2.1 功能增强

### X-Token-Expire-At 响应头

每次 access token 校验通过后，响应头返回剩余有效期（秒）：

```
X-Token-Expire-At: 5400
```

**客户端续期策略**：剩余 < 5min 时主动调 `/v1/auth/refresh`，避免 10201 过期错误中断用户体验。

### revokeByUser — 按用户踢出所有设备

```java
// 管理员强制踢出用户的所有 WEB session
int revoked = generator.revokeByUser("WEB", "user-123");
// → 删除该用户所有 token Redis key + jti 加入撤销 Set
// → 后续请求立即失效（拦截器检查 isRevoked → 10208 TOKEN_REVOKED）
```

适用场景：密码修改后强制重登录 / 管理员封禁用户 / 安全事件应急。

## 相关文档

- [用户指南](./GUIDE.md) — 完整功能说明 + 配置参考
- [快速开始](./QUICKSTART.md) — 5 分钟集成
- [测试文档](./TESTING.md) — 测试场景与覆盖率

## 📚 文档导航

| 我想… | 看这个文档 |
|---|---|
| 5 分钟接入 | [快速开始](./QUICKSTART.md) |
| 完整功能概览 | [用户指南-概览](./GUIDE-OVERVIEW.md) |
| 配置项详解 | [用户指南-配置](./GUIDE-CONFIG.md) |
| Refresh 家族 / 高级特性 | [用户指南-进阶](./GUIDE-ADVANCED.md) |
| 常见问题 / 错误码 | [用户指南-FAQ](./GUIDE-FAQ.md) |
| 测试场景 | [测试文档](./TESTING.md) |
