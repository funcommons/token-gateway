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

## 4. 配置说明

### 4.1 基础配置

```yaml
ldx2t:
  commons:
    redis:
      enabled: true  # 是否启用多 Redis 注入器
      datasources:
        # 数据源名称(必须唯一)
        default:
          host: localhost              # Redis 主机地址
          port: 6379                   # Redis 端口,默认 6379
          password: your_password      # Redis 密码(可选)
          database: 0                  # Redis 数据库索引,默认 0
          timeout: 3000                # 连接超时时间(毫秒),默认 3000
          ssl: false                   # 是否启用 SSL,默认 false
```

### 4.2 多名同源(别名)配置

支持为同一个 Redis 数据源配置多个名称(别名),使用 **`aliases`** 字段指定别名列表。

#### 语法格式

```yaml
ldx2t:
  commons:
    redis:
      datasources:
        cache:  # 主名称
          aliases: [temp, buffer]  # 别名列表
          host: localhost
          port: 6379
```

#### 配置示例

```yaml
ldx2t:
  commons:
    redis:
      enabled: true
      datasources:
        # 主名称:cache,别名:temp
        cache:
          aliases: [temp]  # 别名列表
          host: localhost
          port: 6379
          password: cache_password
          database: 0
          lettuce:
            pool:
              max-active: 8
              max-idle: 8
              min-idle: 2
          redisson:
            enabled: true

        # 主名称:business,别名:order、product
        business:
          aliases: [order, product]  # 多个别名
          host: redis-business.example.com
          port: 6379
          password: business_password
          database: 1
          lettuce:
            pool:
              max-active: 16
              max-idle: 8
              min-idle: 4
```

#### Bean 命名规则

每个名称都会注册独立的 Bean,但它们**共享同一个 Redis 连接实例**。

**示例**:配置 `cache: aliases: [temp]` 会生成以下 Bean:

| Bean 名称 | 类型 | 说明 |
|-----------|------|------|
| cacheRedisTemplate | StringRedisTemplate | 主名称 |
| tempRedisTemplate | StringRedisTemplate | 别名 |
| cacheRedissonClient | RedissonClient | 主名称 |
| tempRedissonClient | RedissonClient | 别名 |

**@Primary 规则**:主名称(`cache`)的 Bean 会被标记为 `@Primary`。

#### 使用示例

```java
@Service
public class CacheService {

    // 使用主名称注入
    @Autowired
    @Qualifier("cacheRedisTemplate")
    private StringRedisTemplate cacheRedis;

    // 使用别名注入
    @Autowired
    @Qualifier("tempRedisTemplate")
    private StringRedisTemplate tempRedis;

    // 不使用 @Qualifier,注入 cache(@Primary)
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void testAliases() {
        // 三个变量都指向同一个 Redis 实例
        cacheRedis.opsForValue().set("key1", "value1");
        tempRedis.opsForValue().set("key2", "value2");
        redisTemplate.opsForValue().set("key3", "value3");

        // 验证是同一个实例
        assert cacheRedis == tempRedis;
        assert cacheRedis == redisTemplate;
    }
}
```

```java
@Service
public class OrderService {

    // 使用主名称 business
    @Autowired
    @Qualifier("businessRedisTemplate")
    private StringRedisTemplate businessRedis;

    // 使用别名 order(更语义化)
    @Autowired
    @Qualifier("orderRedisTemplate")
    private StringRedisTemplate orderRedis;

    // 使用别名 product
    @Autowired
    @Qualifier("productRedisTemplate")
    private StringRedisTemplate productRedis;

    public void saveOrder(Order order) {
        // 三个变量指向同一个 Redis
        // 使用不同名称可以增强代码可读性
        orderRedis.opsForValue().set("order:" + order.getId(), order.toJson());
    }

    public void saveProduct(Product product) {
        // 语义化命名,更清晰
        productRedis.opsForHash().put("products",
            product.getId().toString(),
            product.toJson());
    }
}
```

#### 典型应用场景

**场景一:语义化命名**

```yaml
datasources:
  business:
    aliases: [order, product, inventory]
    host: redis-business.example.com
```

不同模块可以使用不同的语义化名称,提高代码可读性:
- 订单模块使用 `orderRedisTemplate`
- 商品模块使用 `productRedisTemplate`
- 库存模块使用 `inventoryRedisTemplate`

**场景二:平滑迁移和向后兼容**

```yaml
datasources:
  newName:
    aliases: [oldName]
    host: redis-new.example.com
```

在重构时保持向后兼容:
- 新代码使用 `newNameRedisTemplate`
- 旧代码仍可使用 `oldNameRedisTemplate`
- 逐步迁移,避免大规模改动

**场景三:多团队协作**

```yaml
datasources:
  shared:
    aliases: [team-a, team-b]
    host: redis-shared.example.com
```

不同团队使用统一资源但保持各自的命名习惯。

#### 注意事项

- 【推荐】别名不超过 3 个,避免混淆
- 【推荐】主名称使用最常用名称
- 【强制】别名数组格式: `aliases: [name1, name2]`
- 【提示】所有别名共享同一个连接,不会增加资源消耗

### 4.3 连接池配置

```yaml
ldx2t:
  commons:
    redis:
      datasources:
        default:
          host: localhost
          port: 6379
          lettuce:
            pool:
              max-active: 8           # 最大连接数,默认 8
              max-idle: 8             # 最大空闲连接数,默认 8
              min-idle: 0             # 最小空闲连接数,默认 0
              max-wait: -1ms          # 最大等待时间,-1 表示无限等待
              time-between-eviction-runs: 60000  # 空闲连接检查周期(毫秒)
            shutdown-timeout: 100ms   # 关闭超时时间
```

### 4.4 Redisson 配置

```yaml
ldx2t:
  commons:
    redis:
      datasources:
        default:
          host: localhost
          port: 6379
          redisson:
            enabled: true              # 是否启用 Redisson,默认 false
            codec: org.redisson.codec.JsonJacksonCodec  # 序列化编码器
            threads: 16                # Netty 线程数
            netty-threads: 32          # Netty 工作线程数
            transport-mode: NIO        # 传输模式:NIO / EPOLL
```

### 4.5 哨兵模式配置

```yaml
ldx2t:
  commons:
    redis:
      datasources:
        sentinel:
          sentinel:
            master: mymaster           # 哨兵主节点名称
            nodes:                     # 哨兵节点列表
              - sentinel1.example.com:26379
              - sentinel2.example.com:26379
              - sentinel3.example.com:26379
          password: your_password
          database: 0
```

### 4.6 集群模式配置

```yaml
ldx2t:
  commons:
    redis:
      datasources:
        cluster:
          cluster:
            nodes:                     # 集群节点列表
              - redis-node1.example.com:6379
              - redis-node2.example.com:6379
              - redis-node3.example.com:6379
            max-redirects: 3           # 最大重定向次数
          password: your_password
```

### 4.7 配置参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| host | Redis 主机地址 | localhost |
| port | Redis 端口 | 6379 |
| password | Redis 密码 | 无 |
| database | 数据库索引 | 0 |
| timeout | 连接超时(毫秒) | 3000 |
| lettuce.pool.max-active | 最大连接数 | 8 |
| lettuce.pool.max-idle | 最大空闲连接数 | 8 |
| lettuce.pool.min-idle | 最小空闲连接数 | 0 |
| redisson.enabled | 是否启用 Redisson | false |

### 4.8 完整配置示例

```yaml
spring:
  application:
    name: user-service

ldx2t:
  commons:
    redis:
      enabled: true
      datasources:
        # 默认缓存 Redis
        default:
          host: localhost
          port: 6379
          password: cache_password
          database: 0
          timeout: 3000
          lettuce:
            pool:
              max-active: 8
              max-idle: 8
              min-idle: 2
          redisson:
            enabled: false

        # 业务数据 Redis
        business:
          host: redis-business.example.com
          port: 6379
          password: business_password
          database: 1
          lettuce:
            pool:
              max-active: 16
              max-idle: 8
              min-idle: 4
          redisson:
            enabled: true
            threads: 16
            netty-threads: 32

        # 分布式锁 Redis(哨兵模式)
        lock:
          sentinel:
            master: lock-master
            nodes:
              - sentinel1.example.com:26379
              - sentinel2.example.com:26379
              - sentinel3.example.com:26379
          password: lock_password
          database: 0
          redisson:
            enabled: true

        # 会话 Redis(集群模式)
        session:
          cluster:
            nodes:
              - redis-session1.example.com:6379
              - redis-session2.example.com:6379
              - redis-session3.example.com:6379
            max-redirects: 3
          password: session_password
          timeout: 5000
          lettuce:
            pool:
              max-active: 20
              max-idle: 10
              min-idle: 5
```

## 5. 使用指南

### 5.1 Maven 依赖

```xml
<dependency>
    <groupId>com.ldx2t</groupId>
    <artifactId>ldx2t-commons-all</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 5.2 基础使用

> 💡 注入方式详见第2.3节，此处仅展示动态管理用法。

#### 5.2.1 混合使用示例

展示如何在同一个服务中使用多种注入方式：

```java
@Service
public class MixedInjectionService {

    // 方式一:不使用 @Qualifier,注入 default(@Primary)
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 方式四:字段名匹配,注入 business(推荐使用 @Resource)
    @Resource
    private StringRedisTemplate businessRedisTemplate;

    // 方式三:@Qualifier 显式指定,注入 lock
    @Autowired
    @Qualifier("lockRedissonClient")
    private RedissonClient lockClient;

    // 方式五:MultiRedisManager 动态获取
    @Autowired
    private MultiRedisManager redisManager;

    public void processData(String key, String value) {
        // 使用 default Redis 缓存
        redisTemplate.opsForValue().set("cache:" + key, value);

        // 使用 business Redis 存储业务数据
        businessRedisTemplate.opsForValue().set("business:" + key, value);

        // 使用 lock Redis 加锁
        RLock lock = lockClient.getLock("lock:" + key);

        // 动态获取其他数据源
        StringRedisTemplate customTemplate = redisManager.getRedisTemplate("custom");
    }
}
```

#### 5.2.2 使用 MultiRedisManager

```java
@Autowired
private MultiRedisManager redisManager;

// 动态获取指定数据源
StringRedisTemplate template = redisManager.getRedisTemplate("business");
RedissonClient client = redisManager.getRedissonClient("lock");

// 快捷获取默认数据源
StringRedisTemplate defaultTemplate = redisManager.getDefaultRedisTemplate();
RedissonClient defaultClient = redisManager.getDefaultRedissonClient();

// 管理方法
List<String> names = redisManager.getAllDatasourceNames();
boolean exists = redisManager.containsDatasource("business");
boolean healthy = redisManager.checkHealth("business");
```

### 5.3 高级使用

#### 5.3.1 动态添加/删除数据源

```java
// 添加数据源
RedisDataSourceConfig config = RedisDataSourceConfig.builder()
    .name("tenant-" + tenantId)
    .host("redis-host.com")
    .port(6379)
    .build();
redisManager.addDataSource(config);

// 删除数据源
redisManager.removeDatasource("tenant-123");
```

#### 5.3.2 健康检查

```java
@Scheduled(fixedRate = 60000)
public void checkHealth() {
    for (String name : redisManager.getAllDatasourceNames()) {
        if (!redisManager.checkHealth(name)) {
            // 告警处理
        }
    }
}
```

#### 5.3.3 分布式锁

```java
RLock lock = redissonClient.getLock("order:lock:" + orderId);
try {
    if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
        // 业务逻辑
    }
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

#### 5.3.4 Pipeline批量操作

```java
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (User user : users) {
        connection.stringCommands().set(
            ("user:" + user.getId()).getBytes(),
            JSON.toJSONString(user).getBytes()
        );
    }
    return null;
});
```

## 6. Bean 命名规则

- StringRedisTemplate:`{datasourceName}RedisTemplate`
- RedissonClient:`{datasourceName}RedissonClient`

示例:
- `defaultRedisTemplate`
- `defaultRedissonClient`
- `businessRedisTemplate`
- `businessRedissonClient`

## 7. 最佳实践

### 7.1 命名规范

- **数据源名称**:使用小写字母和短横线,如 `default`、`business`、`lock`
- **Bean 名称**:自动生成,遵循 `{datasourceName}RedisTemplate` 和 `{datasourceName}RedissonClient` 格式
- **Redis Key 规范**:遵循 `[业务]:[子模块]:[ID]` 格式

### 7.2 连接池配置建议

| 场景 | max-active | max-idle | min-idle |
|------|------------|----------|----------|
| 低并发(缓存) | 8 | 8 | 2 |
| 中等并发(业务) | 16 | 8 | 4 |
| 高并发(热点) | 32 | 16 | 8 |
| 分布式锁 | 10 | 5 | 2 |

### 7.3 客户端选择建议

| 场景 | 推荐客户端 | 原因 |
|------|-----------|------|
| 简单 K-V 缓存 | StringRedisTemplate | API 简洁,性能好 |
| 分布式锁 | RedissonClient | 提供开箱即用的分布式锁 |
| 分布式集合 | RedissonClient | 支持 RMap、RSet 等 |
| Spring Cache | StringRedisTemplate | 与 @Cacheable 无缝集成 |
| 复杂数据结构 | RedissonClient | 功能更强大 |

### 7.4 注入方式选择建议

根据不同场景选择合适的注入方式:

| 场景 | 推荐方式 | 优先级 | 说明 |
|------|---------|--------|------|
| 一个类一个源 | @RedisOn | ⭐⭐⭐⭐⭐ | 代码最简洁,语义最清晰 |
| 只用 default | 不用注解 | ⭐⭐⭐⭐ | 依赖 @Primary,简单直接 |
| 一个类多个源 | @RedisOn + @Qualifier | ⭐⭐⭐⭐ | 主源用 @RedisOn,其他用 @Qualifier |
| 精确控制注入 | @Qualifier | ⭐⭐⭐⭐ | 明确指定,最灵活 |
| 字段名匹配 | @Resource | ⭐⭐⭐⭐ | 优先按名称注入,可靠绕过 @Primary |
| 动态切换源 | MultiRedisManager | ⭐⭐⭐⭐⭐ | 运行时动态选择 |
| 多租户场景 | MultiRedisManager | ⭐⭐⭐⭐⭐ | 根据租户 ID 动态获取 |
| 代码简洁优先 | @RedisOn | ⭐⭐⭐⭐⭐ | 减少样板代码 |

**推荐**: 一个类用一个源选 `@RedisOn`, 多个源选 `@Qualifier`, 动态切换选 `MultiRedisManager`

### 7.5 安全建议

- 生产环境【强制】配置 Redis 密码
- 不同业务使用不同的 Redis 密码
- 敏感配置使用环境变量或配置中心
- 定期检查 Redis 连接数,避免连接泄漏
- 生产环境禁用 Redis 危险命令(FLUSHALL、FLUSHDB)

### 7.6 性能优化建议

- 合理设置连接池参数,避免频繁创建连接
- 使用 Pipeline 进行批量操作
- 避免大 Key,单个 Key 不超过 1MB
- 设置合理的过期时间,避免内存溢出
- 使用连接池监控,及时发现问题

## 8. 监控与运维

### 8.1 监控指标

暴露以下 Actuator 监控指标:

- `ldx2t.redis.datasource.count`:数据源总数
- `ldx2t.redis.connection.active`:活跃连接数
- `ldx2t.redis.connection.idle`:空闲连接数
- `ldx2t.redis.operation.success`:操作成功次数
- `ldx2t.redis.operation.failure`:操作失败次数
- `ldx2t.redis.health.status`:健康检查状态

### 8.2 日志记录

组件自动记录以下日志:

- 数据源初始化和销毁
- 连接池状态变化
- 健康检查结果
- 异常和错误信息

### 8.3 故障排查

#### 问题一:连接超时

**原因**:
- Redis 服务器不可达
- 网络问题
- 连接池耗尽

**解决方案**:
1. 检查 Redis 服务器状态
2. 检查网络连通性
3. 增大连接池参数
4. 检查是否存在连接泄漏

#### 问题二:注入失败

**原因**:
- 配置错误
- Bean 名称冲突
- 数据源未启用

**解决方案**:
1. 检查 application.yml 配置
2. 确认 `ldx2t.commons.redis.enabled=true`
3. 检查数据源名称是否唯一
4. 查看启动日志确认 Bean 创建情况

## 9. 常见问题

### Q1: 如何在运行时动态添加数据源?

A: 使用 `MultiRedisManager.addDataSource()` 方法。

### Q2: 是否支持 Redis 集群模式?

A: 支持,在配置中使用 `cluster.nodes` 配置集群节点。

### Q3: 如何选择 StringRedisTemplate 还是 RedissonClient?

A: 简单 K-V 操作使用 StringRedisTemplate,分布式锁和高级功能使用 RedissonClient。

### Q4: 连接池参数如何配置?

A: 根据并发量配置,低并发 8/8/2,中等并发 16/8/4,高并发 32/16/8。

### Q5: 如何实现 Redis 读写分离?

A: 配置两个数据源,一个指向主节点,一个指向从节点。

### Q6: 别名配置有什么限制?

A: 推荐别名不超过 3 个,使用 `aliases: [name1, name2]` 数组格式配置。所有别名共享同一个连接实例。

### Q7: @RedisOn 和 @Qualifier 可以混用吗?

A: 可以。`@RedisOn` 指定类的默认数据源,`@Qualifier` 可以覆盖特定字段的数据源。优先级:`@Qualifier` > `@RedisOn` > 字段名匹配(@Resource) > `@Primary`。

### Q8: @RedisOn 如何处理数据源不存在的情况?

A: 默认严格模式(`strict=true`)会在启动时抛出异常。设置 `strict=false` 可以在数据源不存在时自动降级到 default 数据源。

### Q9: 什么场景推荐使用 @RedisOn?

A: 一个类主要使用一个数据源时使用 `@RedisOn`,多个数据源使用 `@Qualifier` 或 `MultiRedisManager`。

### Q10: @RedisOn 会影响性能吗?

A: 不会。仅在 Bean 初始化阶段处理,运行时无额外开销。

### Q11: 字段名匹配时,为什么推荐使用 @Resource 而不是 @Autowired?

A: `@Resource` 优先按名称匹配,可靠绕过 `@Primary`; `@Autowired` 优先按类型匹配,会被 `@Primary` 影响。字段名匹配推荐使用 `@Resource`。
