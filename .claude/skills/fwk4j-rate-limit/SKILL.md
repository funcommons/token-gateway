---
name: fwk4j-rate-limit
description: framework4j 分布式限流（Lua 滑动窗口 ZSET + 固定窗口 + 4 scope + 白名单 + Retry-After 响应头）。触发词：@RateLimit、限流、滑动窗口、令牌桶、固定窗口、scope、whitelist、Retry-After、X-RateLimit、限流白名单、IP 限流、用户限流。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-traffic
  tags: [rate-limit, lua, sliding-window, redis]
  language: zh-CN
  artifactId: framework4j-rate-limit
  config-prefix: framework4j.rate-limit
  examples:
    - "接口限流怎么加"              # → @RateLimit(limit=100, window="1m")
    - "按用户限流"                  # → scope="user"
    - "健康检查不要被限流"          # → whitelist-paths
    - "限流后客户端怎么知道"        # → Retry-After + X-RateLimit-* 响应头
---

# framework4j-rate-limit 限流

## 注解式

```java
@RateLimit(limit = 100, window = "1m", scope = "ip")      // IP 限流
@RateLimit(limit = 10, window = "1s", scope = "user")      // 用户级
@RateLimit(limit = 1000, window = "1m", scope = "global")  // 全局
```

## scope 维度

| scope | key 格式 | 适用 |
|---|---|---|
| `ip`（默认） | `ratelimit:ip:{ip}:{path}` | 公网 IP |
| `user` | `ratelimit:user:{uid}:{path}` | 已登录（X-User-Id） |
| `app` | `ratelimit:app:{ak}:{path}` | 开放 API |
| `global` | `ratelimit:global:global:{path}` | 全局 |

## 白名单（不消耗配额）

```yaml
framework4j:
  rate-limit:
    whitelist-paths: ["/actuator/**", "/health/**"]
    whitelist-ips: ["10.0.0.1"]
```

## 被限流响应

```
HTTP 429
Retry-After: 30
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1718660460
{"code":10500,"message":"请求过于频繁，请 30 秒后重试"}
```

## 编程式（固定窗口）

```java
RateLimitService.AcquireResult r = rateLimitService.tryAcquireFixedWindow(key, 100, 60);
```

## 配置

```yaml
framework4j:
  rate-limit:
    enabled: true
    default-limit: 100
    default-window: "1m"
    default-scope: ip
    redis-name: default
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-rate-limit</artifactId>
    <version>v1.1.1</version>
</dependency>
```
