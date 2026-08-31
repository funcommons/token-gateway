← [返回 README](./README.md)

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

