← [返回 README](./README.md)

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
