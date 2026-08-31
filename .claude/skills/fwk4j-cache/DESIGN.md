← [返回 README](./README.md)

# framework4j-cache 设计文档

> 多级缓存 SDK：Caffeine L1 + Redis L2 + Lua 单飞 + 布隆过滤器防穿透

## 1. 目标

补齐 Spring Cache 缺失能力：
- TTL / 防穿透 / 防击穿 / 防雪崩
- 二级缓存（本地 + 分布式）
- 注解驱动 `@CacheableGet` / `@CacheablePut` / `@CacheableEvict`

## 2. 核心能力

### 2.1 二级缓存

```
应用 → L1 (Caffeine) → L2 (Redis) → 业务方法
       命中即返回         命中回填 L1    未命中走业务
```

- L1 命中：< 1μs（进程内）
- L2 命中：~1ms（网络往返）+ 回填 L1
- 都未命中：走业务方法（DB / RPC）+ 回填 L1+L2

### 2.2 防穿透（空值缓存）

DB 未找到也缓存空值（短 TTL，默认 30s），防止恶意查询打穿。

可选：布隆过滤器（高并发热点场景），启动时预热所有合法 key。

### 2.3 防击穿（单飞）

热点 key 过期瞬间，多个请求同时回源。用 Redis 分布式锁保证只 1 个回源，其余等待。

```
T1 抢锁成功 → 回源 → 回填缓存 → 释放锁
T2/T3 抢锁失败 → 等待 200ms → 重读缓存（T1 已回填）
```

Lua 原子化：`SET lock:key NX EX 3` + 解锁用 Lua（GET==value 才 DEL）。

### 2.4 防雪崩（TTL 抖动）

批量预热的 key 加随机抖动 `ttl ± 10%`，避免同时过期引发雪崩。

## 3. API 设计

### 3.1 注解

```java
@Retention(RUNTIME) @Target(METHOD)
public @interface CacheableGet {
    String prefix();              // key 前缀（如 "user"）
    String key();                 // SpEL（如 "#id"）
    long ttl() default 3600;      // 秒
    long nullTtl() default 30;    // 空值缓存秒数
    boolean l1Enabled() default true;
    boolean singleFlight() default true;
    boolean bloomGuard() default false;
}

@Retention(RUNTIME) @Target(METHOD)
public @interface CacheablePut {
    String prefix();
    String key();
    long ttl() default 3600;
}

@Retention(RUNTIME) @Target(METHOD)
public @interface CacheableEvict {
    String prefix();
    String key();
    boolean evictL2() default true;  // 是否同步删 Redis（默认双删）
}
```

### 3.2 配置

```yaml
framework4j:
  cache:
    enabled: true
    redis-name: default
    default-ttl-seconds: 3600
    null-ttl-seconds: 30
    l1:
      enabled: true
      max-size: 10000              # 每个 prefix 最多缓存多少
      expire-after-write: 600      # Caffeine 写后过期（秒）
    bloom:
      enabled: false               # 默认关闭，热点场景手动开启
    single-flight:
      enabled: true
      lock-ttl-seconds: 3
      wait-millis: 200
```

## 4. Lua 脚本

### 4.1 单飞加锁

```lua
-- KEYS[1] = lock key, ARGV[1] = token (UUID), ARGV[2] = ttl
if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
  return 1
end
return 0
```

### 4.2 单飞解锁（防误删他人锁）

```lua
-- KEYS[1] = lock key, ARGV[1] = token
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
```

## 5. 文件结构

```
framework4j-cache/
├── pom.xml
└── src/main/java/fun/commons/framework4j/cache/
    ├── config/
    │   ├── CacheProperties.java
    │   └── CacheAutoConfiguration.java
    ├── annotation/
    │   ├── @CacheableGet.java
    │   ├── @CacheablePut.java
    │   └── @CacheableEvict.java
    ├── service/
    │   ├── CacheService.java            # L1 + L2 + 单飞 + 防穿透
    │   ├── SingleFlightService.java     # 分布式锁
    │   └── BloomGuardService.java       # 布隆过滤器（可选）
    ├── lua/
    │   └── CacheLuaScripts.java
    └── exception/
        └── CacheException.java
```

## 6. 测试设计

| 测试 | 场景 |
|---|---|
| L1 命中 | 不触 Redis |
| L2 命中回填 L1 | Redis 有 L1 无 |
| 全未命中走业务 | 调用业务方法 + 回填 |
| 防穿透：DB 返回 null 也缓存 | 短 TTL |
| 单飞：50 线程并发只 1 回源 | Lua 锁 |
| 防雪崩：TTL 抖动 ±10% | 多次 set TTL 不全相同 |
| @CacheableEvict 双删 | L1 + L2 |

## 7. 复用资产

- `MultiRedisManager.getStringRedisTemplate()`
- `CachedBodyRequestWrapper`（同 web 模块）
- §3.1 Lua 原子化模式
- §3.5 try-with-resources

## 8. 排除项

- 不实现 L1 集群同步（Caffeine 进程独立）
- 不实现 Redis pub/sub 失效广播（v2 引入）
- 不支持复杂 SpEL（仅 `#param`）
