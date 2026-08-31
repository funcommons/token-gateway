← [返回 README](./README.md)

# 多 Redis 数据源注入器产品文档

## 1. 功能概述

多 Redis 数据源注入器是 LDX2T Commons SDK 的核心组件之一,为企业应用提供多 Redis 实例管理和注入能力。该组件解决了微服务架构中应用需要连接多个 Redis 集群的场景需求。

### 1.1 核心价值

在企业级微服务架构中,不同业务场景通常需要使用不同的 Redis 集群:

- **缓存数据**:使用专用 Redis 集群存储应用缓存
- **业务数据**:使用独立 Redis 集群存储业务核心数据
- **分布式锁**:使用高可用 Redis 集群实现分布式锁
- **会话数据**:使用独立 Redis 存储用户会话
- **消息队列**:使用 Redis 作为轻量级消息队列

传统方案需要在每个应用中手动配置多个 Redis 连接,代码重复且容易出错。多 Redis 数据源注入器提供统一的配置和管理方案,简化开发工作。

## 2. 核心特性

### 2.1 多数据源支持

- 支持在单个应用中配置多个 Redis 数据源
- 每个数据源独立命名,便于识别和使用
- 支持配置不同的连接参数(host、port、password、database)
- 每个数据源独立的连接池配置

### 2.2 双客户端注入

同时支持两种主流 Redis 客户端:

#### StringRedisTemplate(Spring Data Redis)

- 轻量级,适合简单的 K-V 操作
- 与 Spring Cache 无缝集成
- 支持各种数据类型(String、Hash、List、Set、ZSet)
- API 简洁易用

#### RedissonClient(Redisson)

- 功能强大,支持分布式数据结构
- 提供分布式锁(RLock)
- 支持分布式集合(RMap、RSet、RList)
- 提供分布式对象(RBucket、RAtomicLong)

### 2.3 灵活注入方式

支持多种注入方式(从简单到高级):

#### 方式一:默认注入(@Primary)

`default` 数据源自动标记为 **@Primary**,不使用 `@Qualifier` 时默认注入:

```java
@Autowired
private StringRedisTemplate redisTemplate;      // 自动注入 defaultRedisTemplate
@Autowired
private RedissonClient redissonClient;          // 自动注入 defaultRedissonClient
```

**注入优先级**: @Qualifier > @RedisOn > 字段名匹配(@Resource) > @Primary > 抛异常

#### 方式二:@RedisOn 类级别注解(推荐)

类级别指定数据源,类中所有字段自动注入对应数据源,无需每个字段加 `@Qualifier`:

```java
@Service
@RedisOn("cache")
public class CacheService {
    @Autowired
    private StringRedisTemplate redisTemplate;  // 自动注入 cacheRedisTemplate
    @Autowired
    private RedissonClient redissonClient;      // 自动注入 cacheRedissonClient
}
```

**混合使用**: 可用 `@Qualifier` 覆盖特定字段

```java
@Service
@RedisOn("cache")  // 默认 cache
public class MixedService {
    @Autowired
    private StringRedisTemplate redisTemplate;  // cache

    @Autowired
    @Qualifier("businessRedisTemplate")  // 覆盖为 business
    private StringRedisTemplate businessRedis;
}
```

**参数**: `value`=数据源名, `strict`=严格模式(默认true,数据源不存在时抛异常)

#### 方式三:@Qualifier 注解注入

显式指定Bean名称,精确控制注入:

```java
@Autowired
@Qualifier("businessRedisTemplate")
private StringRedisTemplate businessRedis;

@Autowired
@Qualifier("lockRedissonClient")
private RedissonClient lockClient;
```

**适用**: 同一类使用多个数据源、明确性要求高

#### 方式四:字段名匹配注入(@Resource)

字段名与Bean名完全匹配时自动注入。**推荐用 `@Resource`**(优先按名称),可靠绕过 `@Primary`:

```java
import jakarta.annotation.Resource;

@Resource  // 优先按名称,可靠绕过 @Primary
private StringRedisTemplate businessRedisTemplate;  // 注入 businessRedisTemplate

@Resource
private RedissonClient lockRedissonClient;  // 注入 lockRedissonClient

@Autowired  // default 是 @Primary,用 @Autowired 也可以
private StringRedisTemplate defaultRedisTemplate;
```

**注解对比**: `@Resource`(JSR-250)先按名称后按类型, `@Autowired`(Spring)先按类型后按名称

#### 方式五:MultiRedisManager 统一管理

运行时动态获取数据源,适用于多租户/动态切换场景:

```java
@Autowired
private MultiRedisManager redisManager;

// 动态获取
StringRedisTemplate template = redisManager.getRedisTemplate("business");
RedissonClient client = redisManager.getRedissonClient("lock");

// 快捷方法
StringRedisTemplate defaultTemplate = redisManager.getDefaultRedisTemplate();
RedissonClient defaultClient = redisManager.getDefaultRedissonClient();
```

**常用API**: `getRedisTemplate(name)`, `getRedissonClient(name)`, `getDefaultXXX()`, `getAllDatasourceNames()`, `containsDatasource(name)`, `checkHealth(name)`

### 2.4 命名空间隔离

- 基于数据源名称自动生成 Bean 名称
- 避免多个数据源之间的命名冲突
- 命名规则:`{datasourceName}RedisTemplate`、`{datasourceName}RedissonClient`

### 2.5 动态配置

- 支持运行时动态添加新的 Redis 数据源
- 支持动态修改数据源配置
- 支持动态移除数据源
- 提供数据源健康检查功能

### 2.6 连接池管理

- 每个数据源独立的 Lettuce 连接池
- 支持自定义连接池参数(最大连接数、最小空闲连接数等)
- 连接池监控和统计
- 自动连接回收和超时处理

### 2.7 统一规范

- 完全遵循企业开发规范
- 基于 Spring Boot 3.2 和 Java 17
- 使用 @ConfigurationProperties 进行配置管理
- 支持 application.yml 配置

## 3. 应用场景

**典型场景配置**:

```yaml
ldx2t:
  commons:
    redis:
      datasources:
        # 场景1: 缓存与业务分离
        cache:
          host: redis-cache.com
        business:
          host: redis-business.com

        # 场景2: 分布式锁
        lock:
          host: redis-lock.com
          redisson.enabled: true

        # 场景3: 多租户
        tenant-a:
          host: redis-tenant-a.com
          password: pwd_a
        tenant-b:
          host: redis-tenant-b.com
          password: pwd_b

        # 场景4: 读写分离
        master:
          host: redis-master.com
        slave:
          host: redis-slave.com
```

**使用示例**:

```java
// 场景1: 缓存查询
String cached = cacheRedis.opsForValue().get("key");
if (cached == null) {
    cached = businessRedis.opsForValue().get("key");
    cacheRedis.opsForValue().set("key", cached, 300, TimeUnit.SECONDS);
}

// 场景2: 分布式锁
RLock lock = lockRedissonClient.getLock("lock:" + id);
try {
    if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
        // 业务逻辑
    }
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}

// 场景3: 多租户
StringRedisTemplate template = redisManager.getRedisTemplate("tenant-" + tenantId);
template.opsForValue().set(key, value);

// 场景4: 读写分离
masterRedis.opsForValue().set(key, value);  // 写
String data = slaveRedis.opsForValue().get(key);  // 读
```

