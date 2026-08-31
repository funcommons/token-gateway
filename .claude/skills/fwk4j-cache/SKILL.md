---
name: fwk4j-cache
description: framework4j 多级缓存（Caffeine L1 + Redis L2 + Lua 单飞防击穿 + 空值防穿透 + TTL 抖动防雪崩 + 批量预热 + nullTtl 注解级覆盖）。触发词：@CacheableGet、@CacheablePut、@CacheableEvict、CacheService、多级缓存、Caffeine、单飞、防穿透、防击穿、防雪崩、warmup、预热、nullTtl、缓存预热。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-data
  tags: [cache, caffeine, redis, single-flight]
  language: zh-CN
  artifactId: framework4j-cache
  config-prefix: framework4j.cache
  examples:
    - "缓存怎么用"                    # → @CacheableGet / CacheService.get
    - "怕缓存穿透"                    # → 空值标记 + nullTtl
    - "怕缓存击穿"                    # → 单飞 CompletableFuture
    - "怕缓存雪崩"                    # → TTL ±10% 抖动
    - "冷启动预热"                    # → cacheService.warmup
    - "空值缓存时间想自定义"           # → @CacheableGet(nullTtl=5)
---

# framework4j-cache 多级缓存

## 编程式

```java
@Autowired private CacheService cacheService;

// 读（L1 → L2 → loader → 回填）
User user = cacheService.get("user", id, 3600, () -> userMapper.selectById(id), User.class);
// 写
cacheService.put("user", id, 3600, user);
// 删（双删 L1+L2）
cacheService.evict("user", id);
```

## 注解式（AOP）

```java
@CacheableGet(prefix = "user", key = "#id", nullTtl = 5)
public User getUser(String id) { return userMapper.selectById(id); }

@CacheablePut(prefix = "user", key = "#id")
public User updateUser(String id, String name) { ... }

@CacheableEvict(prefix = "user", key = "#id")
public void deleteUser(String id) {}
```

## 三防

| 能力 | 实现 |
|---|---|
| 防穿透 | 空值标记 `__NULL__`（短 TTL，默认 30s，`nullTtl` 可注解级覆盖） |
| 防击穿 | Lua 分布式锁 + per-key `CompletableFuture` 单飞 |
| 防雪崩 | TTL ±10% `ThreadLocalRandom` 抖动 |

## 批量预热

```java
@PostConstruct
void warmup() {
    cacheService.warmup("user", hotIds, 3600,
        id -> userMapper.selectById(id), User.class);
}
```

## 配置

```yaml
framework4j:
  cache:
    enabled: true
    default-ttl-seconds: 3600
    null-ttl-seconds: 30
    l1:
      enabled: true
      max-size: 10000
      expire-after-write: 600
    single-flight:
      enabled: true
      lock-ttl-seconds: 3
      wait-millis: 200
      max-retry: 10
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-cache</artifactId>
    <version>v1.1.1</version>
</dependency>
```
