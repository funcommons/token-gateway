# framework4j-rate-limit

> 分布式限流：滑动窗口（Lua ZSET）+ 响应头三件套（`Retry-After` / `X-RateLimit-*`）

## 简介

mc-api-spec §8.5 强制要求限流响应头三件套 + 信封 10500。Spring Cloud Gateway 限流是基础设施层（IP 级），本模块专注**应用层细粒度**（用户级 / API 级 / 业务级）。

## 算法选型

| 算法 | 场景 | 实现 |
|---|---|---|
| **sliding_window**（默认） | 平滑限流、精确 | Lua ZSET + `ZREMRANGEBYSCORE` + `ZCARD` + `ZADD` + `PEXPIRE` |
| token_bucket | 允许突发 | Redisson `RRateLimiter`（计划支持） |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-rate-limit</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
framework4j:
  rate-limit:
    enabled: true
    path-patterns: ["/v1/**"]
    exclude-path-patterns: ["/v1/auth/login"]
    redis-name: "default"
    default-limit: 100
    default-window: "1m"
    default-algorithm: "sliding_window"
    default-scope: "ip"
    include-headers: true
```

### 3. 注解使用

```java
@RestController
public class OrderController {

    // 默认 IP 限流：100 req/min
    @RateLimit(limit = 100, window = "1m", scope = "ip")
    @PostMapping("/v1/orders")
    public ApiResponse<?> createOrder(...) { ... }

    // 用户级限流：10 req/s（短信发送场景）
    @RateLimit(limit = 10, window = "1s", scope = "user")
    @PostMapping("/v1/sms/send")
    public ApiResponse<?> sendSms(...) { ... }

    // 全局限流：1000 req/min
    @RateLimit(limit = 1000, window = "1m", scope = "global")
    @GetMapping("/v1/health")
    public ApiResponse<?> health() { ... }
}
```

### 4. 被限流响应

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 30
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1718660460

{
  "code": 10500,
  "message": "请求过于频繁，请 30 秒后重试",
  "data": null,
  "error": null,
  "trace_id": "c0a8010116983728001",
  "timestamp": 1718660400000
}
```

## scope 维度

| scope | key 维度 | 适用 |
|---|---|---|
| `ip`（默认） | `ratelimit:ip:{ip}:{path}` | 公网 IP 限流 |
| `user` | `ratelimit:user:{uid}:{path}` | 已登录用户（从 `X-User-Id` 取） |
| `app` | `ratelimit:app:{accessKey}:{path}` | 开放 API 三方限流 |
| `global` | `ratelimit:global:global:{path}` | 全局共享 |

**X-Forwarded-For 优先**（取第一个 IP），其次 `X-Real-IP`，最后 `remoteAddr`。

## 关键设计

### Lua 原子化（遵循 §3.1）

```lua
-- fun.commons.framework4j.ratelimit.lua.RateLimitLuaScripts#SLIDING_WINDOW
local now = tonumber(ARGV[3])
local cutoff = now - tonumber(ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, cutoff)        -- 清旧记录
local count = redis.call('ZCARD', KEYS[1])                -- 统计当前
if count < tonumber(ARGV[2]) then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. math.random())
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
  return {1, count + 1, now + window, limit}              -- 放行
else
  return {0, count, resetAt, limit}                       -- 限流
end
```

### Redis 故障兜底（遵循 §3.5）

Redis 异常时**放行**（避免限流故障拖垮业务）：

```java
} catch (Exception e) {
    log.warn("[RateLimit] Redis sliding_window failed: {}", e.getMessage());
    return new AcquireResult(true, 0, limit, now + windowMs);  // 兜底放行
}
```

### 响应头三件套（mc-api-spec §8.5）

| Header | 说明 |
|---|---|
| `X-RateLimit-Limit` | 窗口内配额上限 |
| `X-RateLimit-Remaining` | 剩余配额 |
| `X-RateLimit-Reset` | 重置时间（Unix 秒） |
| `Retry-After` | 被限流时必返，距下次可请求的秒数 |

## 自动装配

- `RateLimitAutoConfiguration` 注册 `RateLimitService` + `RateLimitKeyResolver` + `RateLimitInterceptor`
- 通过 `framework4j.rate-limit.enabled=false` 关闭

## 拦截器优先级

`RateLimitInterceptor` 优先级**低于** `SignatureInterceptor`（防止未授权调用消耗限流计数）。

## 相关文档

- `Java开发准则.md` §17 限流规范
- `mc-api-spec` §8.5 限流响应头三件套

## v2.1 功能增强

### 白名单豁免

```yaml
framework4j:
  rate-limit:
    whitelist-paths:       # 路径白名单（完全跳过限流）
      - "/actuator/**"
      - "/health/**"
      - "/v1/auth/login"
    whitelist-ips:         # IP 白名单（内部服务豁免）
      - "10.0.0.1"
      - "10.0.0.2"
```

白名单检查在限流计数**之前**执行，不消耗配额。

### 固定窗口算法（fixed_window）

```java
// 编程式调用
RateLimitService.AcquireResult r = rateLimitService.tryAcquireFixedWindow(key, 100, 60);
// 参数：key, limit, windowSeconds
```

| 算法 | 精度 | 允许突发 | 适用场景 |
|---|---|---|---|
| `sliding_window`（默认） | 高 | 否 | API 精确限流 |
| `fixed_window` | 低（窗口边界突刺） | 是 | 简单计数场景 |
