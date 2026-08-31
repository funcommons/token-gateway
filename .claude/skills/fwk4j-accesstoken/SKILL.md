---
name: fwk4j-accesstoken
description: framework4j AccessToken 鉴权（JWT + Redis 双验 + Refresh 家族轮转 + 重用检测 + maxRotations + 踢人 + X-Token-Expire-At）。触发词：AccessToken、@RequiresToken、RefreshToken、TokenPair、JWT、登录、refresh、jti、family、revokeByUser、踢人、强退、X-Token-Expire-At、Token 过期、续期。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-security
  tags: [jwt, token, refresh, auth, redis]
  language: zh-CN
  artifactId: framework4j-accesstoken
  config-prefix: framework4j.access-token
  examples:
    - "用户登录怎么发 Token"          # → generator.generateToken / generateTokenPair
    - "接口加登录校验"                # → @RequiresToken("WEB")
    - "refresh token 怎么轮转"        # → refreshTokenService.refreshAccessToken
    - "管理员踢用户下线"              # → generator.revokeByUser
    - "Token 快过期了前端怎么知道"     # → X-Token-Expire-At 响应头
---

# framework4j-accesstoken 鉴权

## 生成 Token

```java
@Autowired private AccessTokenGenerator generator;
@Autowired private RefreshTokenService refreshTokenService;

// 仅 access token
String token = generator.generateToken("WEB", Map.of("uid", "u-123"));

// access + refresh pair
TokenPair pair = refreshTokenService.generateTokenPair(Map.of("uid", "u-123"));
// pair.accessToken() / pair.refreshToken() / pair.familyId()
```

## 保护接口

```java
@RequiresToken("WEB")  // 校验 access token（type 默认 access）
@GetMapping("/v1/users/{id}")
public ApiResponse<User> getUser(@PathVariable String id) { ... }

@RequiresToken(value = "WEB", type = "refresh")  // refresh 端点
@PostMapping("/v1/auth/refresh")
public ApiResponse<?> refresh(@RequestBody RefreshRequest req) { ... }
```

## Refresh 轮转

```java
TokenPair rotated = refreshTokenService.refreshAccessToken(refreshToken);
// 一次性：旧 jti 标记 consumed，新 jti 写入 family hash
// 重用检测：旧 refresh 被复用 → 撤销整族 + 毒丸
```

## 踢人（强退）

```java
int revoked = generator.revokeByUser("WEB", "user-123");
// 删除该用户所有 session + jti 加入撤销 Set
```

## X-Token-Expire-At 响应头

每次 access token 校验通过，响应头自动返回剩余秒数：
```
X-Token-Expire-At: 5400
```
客户端 < 5min 时主动 refresh。

## 配置

```yaml
framework4j:
  access-token:
    enabled: true
    redis-name: default
    secret-key: ${JWT_SECRET}       # ≥ 32 字符
    hash-salt: ${HASH_SALT}
    policies:
      WEB:
        key: [uid]
        expire-time: 7200            # access TTL 2h
        max-usage: -1                # 无限次
        auto-renew: true
        renew-increment: 1800        # 续期 30min
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-accesstoken</artifactId>
    <version>v1.1.1</version>
</dependency>
```
