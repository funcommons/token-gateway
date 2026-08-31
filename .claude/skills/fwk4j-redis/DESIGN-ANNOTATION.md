← [返回 README](./README.md)

# @RedisOn 注解设计方案

## 1. 设计目标

通过类级别的 `@RedisOn` 注解指定 Redis 数据源，自动注入对应的 RedisTemplate 和 RedissonClient，避免在每个字段上重复使用 `@Qualifier` 或字段名匹配。

**使用效果对比：**

```java
// 传统方式 - 需要在每个字段上指定
@Service
public class OrderService {
    @Autowired
    @Qualifier("cacheRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("cacheRedissonClient")
    private RedissonClient redissonClient;
}

// 新方式 - 只需在类上指定一次
@Service
@RedisOn("cache")
public class OrderService {
    @Autowired
    private StringRedisTemplate redisTemplate;  // 自动注入 cacheRedisTemplate

    @Autowired
    private RedissonClient redissonClient;      // 自动注入 cacheRedissonClient
}
```

## 2. 核心组件设计

### 2.1 @RedisOn 注解定义

```java
package com.ldx2t.commons.redis.annotation;

import java.lang.annotation.*;

/**
 * Redis 数据源选择注解
 *
 * 用于指定当前类使用的 Redis 数据源名称。
 * 标注此注解后，类中注入的 StringRedisTemplate 和 RedissonClient
 * 会自动使用指定数据源的实例。
 *
 * @author LDX2T
 * @since 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RedisOn {

    /**
     * Redis 数据源名称
     *
     * @return 数据源名称，必须在配置文件中已定义
     */
    String value();

    /**
     * 是否严格模式
     *
     * 严格模式下，如果指定的数据源不存在，会在启动时抛出异常。
     * 非严格模式下，会使用默认数据源。
     *
     * @return true-严格模式，false-宽松模式
     */
    boolean strict() default true;
}
```

### 2.2 BeanPostProcessor 实现

```java
package com.ldx2t.commons.redis.config;

import com.ldx2t.commons.redis.annotation.RedisOn;
import com.ldx2t.commons.redis.manager.MultiRedisManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

/**
 * Redis 数据源注入处理器
 *
 * 处理 @RedisOn 注解，自动注入指定数据源的 Redis 客户端
 *
 * @author LDX2T
 * @since 1.0.0
 */
@Slf4j
@Component
public class RedisOnAnnotationBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware {

    private ApplicationContext applicationContext;
    private MultiRedisManager multiRedisManager;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Class<?> clazz = bean.getClass();

        // 检查类上是否有 @RedisOn 注解
        RedisOn redisOn = clazz.getAnnotation(RedisOn.class);
        if (redisOn == null) {
            return bean;
        }

        String datasourceName = redisOn.value();
        boolean strict = redisOn.strict();

        // 延迟获取 MultiRedisManager（避免循环依赖）
        if (multiRedisManager == null) {
            multiRedisManager = applicationContext.getBean(MultiRedisManager.class);
        }

        // 验证数据源是否存在
        if (!multiRedisManager.containsDatasource(datasourceName)) {
            String errorMsg = String.format(
                    "Redis 数据源 [%s] 不存在，类 [%s] 使用了 @RedisOn 注解",
                    datasourceName, clazz.getName()
            );

            if (strict) {
                throw new IllegalStateException(errorMsg);
            } else {
                log.warn("{}, 将使用默认数据源", errorMsg);
                datasourceName = "default";
            }
        }

        // 处理字段注入
        injectRedisFields(bean, datasourceName);

        log.debug("类 [{}] 已自动注入 Redis 数据源 [{}]", clazz.getName(), datasourceName);

        return bean;
    }

    /**
     * 注入 Redis 相关字段
     */
    private void injectRedisFields(Object bean, String datasourceName) {
        ReflectionUtils.doWithFields(bean.getClass(), field -> {
            // 确保字段可访问
            ReflectionUtils.makeAccessible(field);

            // 跳过已经有值的字段
            if (ReflectionUtils.getField(field, bean) != null) {
                return;
            }

            Class<?> fieldType = field.getType();
            Object value = null;

            // 注入 StringRedisTemplate
            if (fieldType.equals(StringRedisTemplate.class)) {
                value = multiRedisManager.getRedisTemplate(datasourceName);
            }
            // 注入 RedissonClient
            else if (fieldType.equals(RedissonClient.class)) {
                value = multiRedisManager.getRedissonClient(datasourceName);
            }

            if (value != null) {
                ReflectionUtils.setField(field, bean, value);
                log.trace("已注入字段 [{}] 类型 [{}] 数据源 [{}]",
                        field.getName(), fieldType.getSimpleName(), datasourceName);
            }
        }, field -> {
            // 只处理带有 @Autowired 注解的字段
            return field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class);
        });
    }
}
```

### 2.3 配置类

```java
package com.ldx2t.commons.redis.config;

import com.ldx2t.commons.redis.config.RedisOnAnnotationBeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @RedisOn 注解自动配置
 *
 * @author LDX2T
 * @since 1.0.0
 */
@Configuration
@ConditionalOnProperty(
        prefix = "ldx2t.commons.redis",
        name = "enabled",
        havingValue = "true"
)
public class RedisOnAnnotationAutoConfiguration {

    @Bean
    public RedisOnAnnotationBeanPostProcessor redisOnAnnotationBeanPostProcessor() {
        return new RedisOnAnnotationBeanPostProcessor();
    }
}
```

## 3. 使用示例

### 3.1 基础使用

```java
@Service
@RedisOn("cache")
public class CacheService {

    @Autowired
    private StringRedisTemplate redisTemplate;  // 自动注入 cacheRedisTemplate

    @Autowired
    private RedissonClient redissonClient;      // 自动注入 cacheRedissonClient

    public void set(String key, String value) {
        redisTemplate.opsForValue().set(key, value, 300, TimeUnit.SECONDS);
    }

    public void lock(String lockKey) {
        RLock lock = redissonClient.getLock(lockKey);
        // 使用分布式锁...
    }
}
```

### 3.2 业务数据存储

```java
@Service
@RedisOn("business")
public class OrderService {

    @Autowired
    private StringRedisTemplate redisTemplate;  // 自动注入 businessRedisTemplate

    @Autowired
    private RedissonClient redissonClient;      // 自动注入 businessRedissonClient

    public void saveOrder(Order order) {
        String key = "order:" + order.getId();
        String value = JSON.toJSONString(order);
        redisTemplate.opsForValue().set(key, value);
    }

    public void saveToMap(Order order) {
        RMap<String, Order> orderMap = redissonClient.getMap("orders");
        orderMap.put(order.getId().toString(), order);
    }
}
```

### 3.3 分布式锁专用

```java
@Service
@RedisOn("lock")
public class InventoryService {

    @Autowired
    private RedissonClient redissonClient;  // 自动注入 lockRedissonClient

    public void deductInventory(Long productId, Integer quantity) {
        RLock lock = redissonClient.getLock("inventory:lock:" + productId);
        try {
            if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
                doDeduct(productId, quantity);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("操作被中断");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
```

### 3.4 混合使用（同一个类使用多个数据源）

当同一个类需要使用多个数据源时，仍然可以使用 `@Qualifier` 显式指定：

```java
@Service
@RedisOn("cache")  // 默认使用 cache 数据源
public class MixedService {

    // 使用默认的 cache 数据源
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 显式指定使用 business 数据源
    @Autowired
    @Qualifier("businessRedisTemplate")
    private StringRedisTemplate businessRedis;

    // 显式指定使用 lock 数据源
    @Autowired
    @Qualifier("lockRedissonClient")
    private RedissonClient lockClient;

    public void process(String key, String value) {
        // 使用 cache 数据源
        redisTemplate.opsForValue().set("cache:" + key, value);

        // 使用 business 数据源
        businessRedis.opsForValue().set("business:" + key, value);

        // 使用 lock 数据源
        RLock lock = lockClient.getLock("lock:" + key);
    }
}
```

### 3.5 非严格模式

```java
@Service
@RedisOn(value = "unknown", strict = false)  // 数据源不存在时使用 default
public class FallbackService {

    @Autowired
    private StringRedisTemplate redisTemplate;  // 会降级到 defaultRedisTemplate

    public void cacheData(String key, String value) {
        redisTemplate.opsForValue().set(key, value);
    }
}
```

## 4. 配置示例

```yaml
ldx2t:
  commons:
    redis:
      enabled: true
      datasources:
        # 默认数据源
        default:
          host: localhost
          port: 6379

        # 缓存数据源
        cache:
          host: redis-cache.example.com
          port: 6379
          password: cache_password

        # 业务数据源
        business:
          host: redis-business.example.com
          port: 6379
          password: business_password
          redisson:
            enabled: true

        # 分布式锁数据源
        lock:
          host: redis-lock.example.com
          port: 6379
          password: lock_password
          redisson:
            enabled: true
```

## 5. 注入优先级规则

当一个类同时使用多种注入方式时，优先级如下：

1. **@Qualifier 显式指定** - 最高优先级，明确指定 Bean 名称
2. **@RedisOn + @Autowired** - 类级别数据源 + 字段注入
3. **字段名匹配** - 字段名与 Bean 名称完全匹配
4. **@Primary 默认注入** - 使用 default 数据源

```java
@Service
@RedisOn("cache")
public class PriorityDemo {

    // 优先级1: @Qualifier 显式指定 -> 使用 businessRedisTemplate
    @Autowired
    @Qualifier("businessRedisTemplate")
    private StringRedisTemplate explicitRedis;

    // 优先级2: @RedisOn 指定 -> 使用 cacheRedisTemplate
    @Autowired
    private StringRedisTemplate redisTemplate;

    // 优先级3: 字段名匹配 -> 使用 lockRedissonClient
    @Autowired
    private RedissonClient lockRedissonClient;
}
```

## 6. 优势与局限

### 6.1 优势

1. **代码简洁** - 不需要在每个字段上重复 `@Qualifier`
2. **语义清晰** - 类级别声明更直观，一看就知道这个类使用哪个数据源
3. **易于维护** - 切换数据源只需修改一处注解
4. **向后兼容** - 与现有的 `@Qualifier` 方式完全兼容
5. **灵活降级** - 支持非严格模式，数据源不存在时自动降级

### 6.2 局限性

1. **单一数据源类** - 适合一个类主要使用一个数据源的场景
2. **字段类型限制** - 只支持 `StringRedisTemplate` 和 `RedissonClient` 两种类型
3. **动态切换受限** - 不适合需要运行时动态切换数据源的场景（此时应使用 `MultiRedisManager`）

### 6.3 适用场景

**推荐使用 @RedisOn：**
- 业务类主要使用单一 Redis 数据源
- 希望代码更简洁清晰
- 数据源在编译期确定

**推荐使用 @Qualifier：**
- 一个类需要使用多个不同的 Redis 数据源
- 需要精确控制每个字段的注入源

**推荐使用 MultiRedisManager：**
- 需要运行时动态选择数据源
- 多租户场景，数据源名称由参数决定
- 需要获取所有数据源列表或进行健康检查

## 7. 实现注意事项

### 7.1 循环依赖问题

BeanPostProcessor 不能在构造函数中注入 MultiRedisManager，需要延迟获取：

```java
@Override
public Object postProcessBeforeInitialization(Object bean, String beanName) {
    // 延迟获取，避免循环依赖
    if (multiRedisManager == null) {
        multiRedisManager = applicationContext.getBean(MultiRedisManager.class);
    }
    // ...
}
```

### 7.2 字段注入时机

需要在 Bean 初始化之前（`postProcessBeforeInitialization`）进行注入，确保在业务方法调用前已完成注入。

### 7.3 已注入字段跳过

如果字段已经有值（通过其他方式注入），应该跳过，避免覆盖：

```java
if (ReflectionUtils.getField(field, bean) != null) {
    return;  // 跳过已有值的字段
}
```

### 7.4 日志记录

建议记录详细的注入日志，便于排查问题：

```java
log.debug("类 [{}] 已自动注入 Redis 数据源 [{}]", clazz.getName(), datasourceName);
log.trace("已注入字段 [{}] 类型 [{}] 数据源 [{}]",
    field.getName(), fieldType.getSimpleName(), datasourceName);
```

