← [返回 README](./README.md)

## 8. 测试用例

### 8.1 单元测试

```java
@SpringBootTest
class RedisOnAnnotationTest {

    @Autowired
    private CacheService cacheService;

    @Autowired
    private MultiRedisManager multiRedisManager;

    @Test
    void testRedisOnAnnotation() {
        // 验证注入的是正确的数据源
        StringRedisTemplate cacheTemplate = multiRedisManager.getRedisTemplate("cache");

        cacheService.set("test-key", "test-value");

        String value = cacheTemplate.opsForValue().get("test-key");
        assertEquals("test-value", value);
    }

    @Test
    void testDataSourceNotFound() {
        // 测试数据源不存在的情况
        assertThrows(IllegalStateException.class, () -> {
            applicationContext.getBean(InvalidDataSourceService.class);
        });
    }
}

@Service
@RedisOn(value = "non-existent", strict = true)
class InvalidDataSourceService {
    @Autowired
    private StringRedisTemplate redisTemplate;
}
```

## 9. 文档更新建议

需要在原产品文档的 **2.3 灵活注入方式** 章节增加第五种方式：

### 方式五：@RedisOn 类级别注解

使用 `@RedisOn` 注解在类级别指定数据源，类中所有 `@Autowired` 的 Redis 客户端会自动注入指定数据源的实例。

```java
@Service
@RedisOn("cache")
public class CacheService {

    @Autowired
    private StringRedisTemplate redisTemplate;  // 自动注入 cacheRedisTemplate

    @Autowired
    private RedissonClient redissonClient;      // 自动注入 cacheRedissonClient

    public void cacheData(String key, String value) {
        redisTemplate.opsForValue().set(key, value, 300, TimeUnit.SECONDS);
    }
}
```

**优势**：
- 代码更简洁，不需要在每个字段上添加 `@Qualifier`
- 语义清晰，一眼就能看出这个类使用哪个数据源
- 与其他注入方式完全兼容，可以混合使用

**适用场景**：
- 业务类主要使用单一 Redis 数据源
- 追求代码简洁性和可读性
- 数据源在编译期确定

## 10. 总结

`@RedisOn` 注解提供了一种更简洁、更优雅的方式来指定 Redis 数据源：

| 特性 | @RedisOn | @Qualifier | 字段名匹配 | MultiRedisManager |
|------|----------|-----------|----------|------------------|
| 代码简洁性 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ |
| 语义清晰性 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| 灵活性 | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| 动态能力 | ⭐ | ⭐ | ⭐ | ⭐⭐⭐⭐⭐ |
| 学习成本 | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ |

**推荐使用场景：**
- 新项目或新模块优先使用 `@RedisOn`
- 业务类主要使用单一数据源时使用 `@RedisOn`
- 需要使用多个数据源时混合使用 `@RedisOn` + `@Qualifier`
- 需要动态切换数据源时使用 `MultiRedisManager`
