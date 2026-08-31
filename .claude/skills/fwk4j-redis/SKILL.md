---
name: fwk4j-redis
description: framework4j 多 Redis 数据源管理（MultiRedisManager + @RedisOn 注解注入 + Lettuce/Redisson + STRING/OBJECT 模板类型 + 健康检查 + 动态添加/删除）。触发词：@RedisOn、MultiRedisManager、多 Redis、Redis 数据源、Redisson、StringRedisTemplate、RedisTemplate、template-type、Redis 连接池、Redis 健康检查。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-data
  tags: [redis, multi-datasource, lettuce, redisson]
  language: zh-CN
  artifactId: framework4j-redis
  config-prefix: framework4j.redis
  examples:
    - "多 Redis 数据源怎么配"          # → datasources map
    - "按名字注入 RedisTemplate"       # → @RedisOn("cache")
    - "Redis 健康检查"                # → checkHealth
    - "STRING 和 OBJECT 模板区别"      # → template-type
---

# framework4j-redis 多 Redis 数据源

## 配置

```yaml
framework4j:
  redis:
    enabled: true
    datasources:
      default:                        # STRING 类型（StringRedisTemplate）
        host: localhost
        port: 6379
        template-type: string
      cache:                          # OBJECT 类型（RedisTemplate<String, Object>）
        host: cache.redis.com
        database: 1
        template-type: object
      session:
        host: session.redis.com
        database: 2
        redisson:
          enabled: true               # 启用 Redisson 分布式锁
```

## @RedisOn 注解注入

```java
@Service
public class OrderService {
    @RedisOn("default")
    private StringRedisTemplate defaultTemplate;

    @RedisOn("cache")
    private RedisTemplate<String, Object> cacheTemplate;

    @RedisOn(value = "missing", strict = false)  // strict=false 缺失则 fallback default
    private StringRedisTemplate optionalTemplate;
}
```

## 健康检查

```java
boolean ok = multiRedisManager.checkHealth("default");
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-redis</artifactId>
    <version>v1.1.1</version>
</dependency>
```
