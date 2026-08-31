# framework4j-cache

> 多级缓存 SDK：Caffeine L1 + Redis L2 + Lua 单飞防击穿 + 空值防穿透 + TTL 抖动防雪崩

## 简介

补齐 Spring Cache 缺失能力：TTL / 防穿透 / 防击穿 / 防雪崩 / 二级缓存 / 注解驱动。

## 核心架构

```
应用 → L1 (Caffeine) → L2 (Redis) → 业务方法
       命中即返回         命中回填 L1    未命中走业务
                                       ↓
                                单飞（Redis 锁）
                                       ↓
                              leader 回源 → 回填 L1+L2
                              follower 等待 → 读缓存
```

## 三防能力

| 能力 | 实现 |
|---|---|
| **防穿透** | DB 未找到也缓存空值标记（短 TTL 30s） |
| **防击穿** | Redis 分布式锁（Lua SET NX EX）+ follower 轮询等待 |
| **防雪崩** | TTL ±10% 随机抖动 |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-cache</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

```yaml
framework4j:
  cache:
    enabled: true
    redis-name: default
    default-ttl-seconds: 3600
    null-ttl-seconds: 30
    key-prefix: "cache"
    l1:
      enabled: true
      max-size: 10000              # 每个 prefix 最多缓存
      expire-after-write: 600      # Caffeine 写后过期（秒）
    single-flight:
      enabled: true
      lock-ttl-seconds: 3
      wait-millis: 200
      max-retry: 10
```

### 3. 编程式使用

```java
@Service
public class UserService {

    @Autowired
    private CacheService cacheService;

    public User getUser(String id) {
        return cacheService.get("user", id, 3600,
                () -> userMapper.selectById(id),  // loader（全未命中走 DB）
                User.class);
    }

    public void updateUser(User user) {
        userMapper.updateById(user);
        cacheService.put("user", user.getId(), 3600, user);
    }

    public void deleteUser(String id) {
        userMapper.deleteById(id);
        cacheService.evict("user", id);  // 双删 L1+L2
    }
}
```

## 关键设计

### Lua 单飞原子化（遵循 Java开发准则 §3.1）

```lua
-- 加锁：SET NX EX 原子
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then return 1
else return 0 end

-- 解锁：GET == token 才 DEL（防误删他人锁）
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1])
else return 0 end
```

### Redis 故障兜底（遵循 Java开发准则 §3.5）

Redis 异常时**放行**（避免缓存故障拖垮业务）：

```java
} catch (Exception e) {
    log.warn("[Cache] SingleFlight lock failed: {}", e.getMessage());
    return null;  // 放行（follower fallback 走 loader）
}
```

## 自动装配

- `CacheAutoConfiguration` 注册 `CacheService` + `SingleFlightService`
- `framework4j.cache.enabled=false` 关闭（默认开启）
- `InMemoryAuditSink` 默认实现（开发/测试用），生产应替换为 DB/Kafka

## 排除项（v2.1）

- 不实现 L1 集群同步（Caffeine 进程独立）
- 不实现 Redis pub/sub 失效广播（v2 引入）
- 不支持复杂 SpEL（仅方法参数 `#param`）

## 相关文档

- `DESIGN.md` 详细设计
- `Java开发准则.md` §3 Redis + Lua 原子化

## v2.1 功能增强

### 批量预热

```java
@PostConstruct
public void warmupCache() {
    List<String> hotUserIds = userMapper.selectHotUserIds();
    cacheService.warmup("user", hotUserIds, 3600,
        id -> userMapper.selectById(id), User.class);
    // → 启动时批量加载热点数据到缓存，避免冷启动回源风暴
}
```

### 注解级空值 TTL 覆盖

```java
// 热点商品：空值缓存 5 秒（快速失效，用户可重试）
@CacheableGet(prefix = "product", key = "#id", nullTtl = 5)
public Product getProduct(String id) { ... }

// 冷数据：空值缓存 5 分钟（减少 DB 查询压力）
@CacheableGet(prefix = "config", key = "#key", nullTtl = 300)
public Config getConfig(String key) { ... }
```

默认 `nullTtl=30` 秒（全局 `framework4j.cache.null-ttl-seconds`），注解级覆盖优先。

## 相关文档

- [缓存设计文档](./DESIGN.md) — L1/L2 架构、单飞防击穿、TTL 抖动防雪崩、Pub/Sub 广播

## 📚 文档导航

| 我想… | 看这个文档 |
|---|---|
| L1/L2 架构 / 单飞 / 防雪崩 | [缓存设计文档](./DESIGN.md) |
