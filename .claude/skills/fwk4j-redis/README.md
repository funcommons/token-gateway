# framework4j-redis

> 多 Redis 数据源管理器：Lettuce + Redisson 双客户端 + `@RedisOn` 注解（类级 / 字段级自动路由）。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | `MultiRedisManager`（多 Redis 实例管理）/ `@RedisOn("name")` 注解处理器 / 每数据源独立 Lettuce + Redisson / 动态添加 / 移除 / `JsonRedisSerializer`（Jackson） |
| 配置前缀 | `framework4j.redis.*` |
| 必需依赖 | `spring-boot-starter-data-redis`、`commons-pool2`、`redisson-spring-boot-starter`、`jackson-databind` |
| 可选依赖 | `framework4j-api` |
| 在 SDK 中的位置 | 缓存层，被 `framework4j-accesstoken` 强依赖 |

**核心原则**：一个 `@RedisOn` 注解解决多 Redis 切换，无需手写 `@Qualifier`。BeanPostProcessor 在 Bean 初始化前注入正确的 `StringRedisTemplate` / `RedisTemplate` / `RedissonClient`。

## 2. 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-redis</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最小 application.yml

```yaml
spring:
  application:
    name: my-app

framework4j:
  redis:
    enabled: true
    datasources:
      default:
        host: localhost
        port: 6379
        database: 0
        password: ${REDIS_PASSWORD:}
        lettuce:
          pool:
            max-active: 20
            max-idle: 10
            min-idle: 5
            max-wait: 5000
        timeout: 5s
        redisson:
          enabled: true   # 启用 Redisson 客户端（分布式锁/集合）
      cache:
        host: cache-host
        port: 6379
        database: 1
        template-type: object  # 用 RedisTemplate<String, Object>（Jackson 序列化）
```

### 最小代码示例

```java
// 类级：整个 Service 用 default 数据源
@Service
@RedisOn("default")
public class UserService {
    @Resource
    private StringRedisTemplate redis;  // 自动走 default
    
    public String getUserName(Long id) {
        return redis.opsForValue().get("user:" + id);
    }
}

// 字段级：单字段切换
@Service
public class CacheService {
    @RedisOn("cache")
    private RedisTemplate<String, Object> objectRedis;  // 走 cache + Jackson 序列化
    
    public void saveUser(UserDTO user) {
        objectRedis.opsForValue().set("user:" + user.getId(), user);
    }
}
```

## 3. 配置参考

### 全局

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.redis.enabled` | `boolean` | `false` | 是否启用（opt-in） |
| `framework4j.redis.primary.template` | `String` | `defaultRedisTemplate` | `@Primary` RedisTemplate |
| `framework4j.redis.primary.client` | `String` | `defaultRedissonClient` | `@Primary` RedissonClient |

### 每数据源

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.redis.datasources.<name>.host` | `String` | 必填 | Redis 主机 |
| `framework4j.redis.datasources.<name>.port` | `int` | `6379` | 端口 |
| `framework4j.redis.datasources.<name>.database` | `int` | `0` | 数据库编号 |
| `framework4j.redis.datasources.<name>.password` | `String` | — | 密码（环境变量） |
| `framework4j.redis.datasources.<name>.timeout` | `Duration` | `5s` | 命令超时 |
| `framework4j.redis.datasources.<name>.template-type` | `TemplateType` | `STRING` | `STRING`（`StringRedisTemplate`）/ `OBJECT`（`RedisTemplate<String, Object>` Jackson） |
| `framework4j.redis.datasources.<name>.aliases` | `List<String>` | — | 别名 |
| `framework4j.redis.datasources.<name>.lettuce.pool.*` | — | — | Lettuce 连接池 |
| `framework4j.redis.datasources.<name>.redisson.enabled` | `boolean` | `false` | 是否启用 Redisson |

## 4. API 参考

### `@RedisOn`（注解）

```java
@Target({TYPE, FIELD})
@Retention(RUNTIME)
public @interface RedisOn {
    String value();              // 数据源名
    boolean strict() default true;  // true: 不存在抛异常; false: 回退 default
}
```

**类级**：类中所有 `StringRedisTemplate` / `RedisTemplate` / `RedissonClient` 字段自动注入指定数据源。
**字段级**：仅该字段注入（优先于类级）。

### `MultiRedisManager`

```java
public class MultiRedisManager {
    public StringRedisTemplate getStringRedisTemplate();
    public StringRedisTemplate getStringRedisTemplate(String name);
    public RedisTemplate<String, Object> getObjectRedisTemplate();
    public RedisTemplate<String, Object> getObjectRedisTemplate(String name);
    public RedisTemplate<String, Object> getOrCreateObjectTemplate(String name);  // STRING 也能转 OBJECT
    public RedissonClient getRedissonClient(String name);
    public RedissonClient getDefaultRedissonClient();
    
    public boolean containsDatasource(String name);
    public List<String> getAllDatasourceNames();
    
    public void addDataSource(RedisDataSourceProperties config);  // 动态添加
    public void removeDatasource(String name);                   // 动态移除
    public boolean checkHealth(String name);
}
```

### `JsonRedisSerializer`（Jackson）

替代 fastjson2，消除 autotype RCE 风险：

```java
public class JsonRedisSerializer<T> implements RedisSerializer<T> {
    // 使用 Jackson + activateDefaultTyping(NON_FINAL)
    // BasicPolymorphicTypeValidator.allowIfBaseType(Object.class)
    // 写入 @class 字段标识具体类型，反序列化还原为原对象
}
```

### `RedisOnBeanPostProcessor`

`BeanPostProcessor`，扫描 `@RedisOn` 注解，在 `postProcessBeforeInitialization` 注入对应实例。`strict=false` 时回退 `default`。类型校验：`StringRedisTemplate` 字段不能注入 OBJECT 数据源（抛异常提示）。

## 5. 示例

### 5.1 缓存 + 锁分离

```yaml
framework4j:
  redis:
    datasources:
      cache:
        host: cache-host
        template-type: object  # 缓存对象
      lock:
        host: lock-host
        redisson:
          enabled: true  # 专用锁
```

```java
@Service
public class OrderService {
    @RedisOn("cache")
    private RedisTemplate<String, Object> cache;
    
    @RedisOn("lock")
    private RedissonClient lockClient;
    
    public OrderDO createOrder(Long userId) {
        RLock lock = lockClient.getLock("order:lock:" + userId);
        try {
            lock.lock(10, TimeUnit.SECONDS);
            // 业务逻辑
            cache.opsForValue().set("order:recent:" + userId, orderVo, 1, TimeUnit.HOURS);
            return order;
        } finally {
            lock.unlock();
        }
    }
}
```

### 5.2 动态添加数据源（多租户）

```java
@Service
public class TenantRedisService {
    @Resource
    private MultiRedisManager manager;
    
    public void registerTenant(String tenantId, String host, int port) {
        RedisDataSourceProperties config = new RedisDataSourceProperties();
        config.setName(tenantId);
        config.setHost(host);
        config.setPort(port);
        config.setTemplateType(TemplateType.STRING);
        config.setLettuce(new LettuceConfig());
        config.setRedisson(new RedissonConfig());
        manager.addDataSource(config);
        // 失败时自动回滚（销毁连接池 + Redisson shutdown）
    }
}
```

### 5.3 STRING 数据源临时用 OBJECT 模板

```java
@Service
public class CacheService {
    @Resource
    private MultiRedisManager manager;
    
    public void saveObject(Long id, Object obj) {
        // default 是 STRING 类型，但临时需要存对象
        RedisTemplate<String, Object> tpl = manager.getOrCreateObjectTemplate("default");
        tpl.opsForValue().set("obj:" + id, obj);
    }
}
```

`getOrCreateObjectTemplate` 会动态创建 OBJECT 模板并缓存（用 default 数据源的连接工厂），不修改原数据源配置。

## 6. 错误码

本模块抛 `RedisDataSourceException`（`RuntimeException` 子类），错误消息含数据源名 + 原因。建议在 `GlobalExceptionHandler` 中捕获并映射到 `10900 INTERNAL_ERROR`。

| 场景 | 异常 | 建议 code |
|---|---|---|
| 数据源不存在 + `strict=true` | `RuntimeException` | `10400 NOT_FOUND` |
| 类型不匹配（STRING 字段注入 OBJECT 数据源） | `RuntimeException` | `10102 FORMAT_INVALID` |
| 动态添加失败 | `RedisDataSourceException` | `10900 INTERNAL_ERROR` |

## 7. FAQ

**Q1：为什么用 Jackson 不用 fastjson2？**
A：fastjson2 的 `autotype` 机制有 RCE 风险（CVE-2022-25845）。Jackson 用 `activateDefaultTyping` + `BasicPolymorphicTypeValidator` 限制只允许 `Object` 基类型，安全且功能等价。

**Q2：`StringRedisTemplate` 和 `RedisTemplate<String, Object>` 怎么选？**
A：存字符串 / 简单值用 `STRING`（性能好）。存对象 / 复杂结构用 `OBJECT`（自动 Jackson 序列化）。同一数据源不能两种模板共存（类型安全检查会拦截）。

**Q3：Redisson 是必需的吗？**
A：不是。`redisson.enabled=false` 时只创建 Lettuce 客户端。需要分布式锁 / `RMap` / `RBucket` 等高级特性时启用。

**Q4：`@RedisOn` 和 `@Qualifier` 区别？**
A：`@RedisOn` 支持类级 + 字段级 + `strict` 回退；`@Qualifier` 仅字段级。SDK 推荐 `@RedisOn`。

**Q5：动态添加数据源失败会回滚吗？**
A：会。`addDataSource` 内部原子注册（template + redisson 同时成功才算成功）。Redisson 失败时回滚已注册的 template + 销毁连接工厂。

## 相关文档

- [产品设计文档](./DESIGN.md) — 多 Redis 数据源注入器架构
- [@RedisOn 注解设计](./DESIGN-ANNOTATION.md) — 注解注入器 BeanPostProcessor 设计

## 📚 文档导航

| 我想… | 看这个文档 |
|---|---|
| 了解核心特性 | [特性与场景](./DESIGN-FEATURES.md) |
| 查配置项 | [配置说明](./DESIGN-CONFIG.md) |
| 学注入方式 | [使用指南](./DESIGN-USAGE.md) |
| @RedisOn 注解原理 | [注解设计文档](./DESIGN-ANNOTATION.md) |
