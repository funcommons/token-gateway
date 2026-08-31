← [返回 README](./README.md)

## 9. 常见问题

### Q1: 如何在运行时动态添加数据源?

A: 使用 `MultiDataSourceManager.addDataSource()` 方法。

### Q2: 是否支持读写分离?

A: 支持,配置两个数据源,一个指向主库,一个指向从库。

### Q3: 如何选择使用哪个数据源?

A: 简单场景使用 @DataSourceOn 注解,复杂场景使用 @Qualifier 或 MultiDataSourceManager。

### Q4: 连接池参数如何配置?

A: 根据并发量配置,低并发 3/3/10,中等并发 5/5/20,高并发 10/10/50。

### Q5: 如何实现多数据源事务?

A: 使用 @Transactional 明确指定事务管理器名称,如 `@Transactional("businessTransactionManager")`。

### Q6: 别名配置有什么限制?

A: 推荐别名不超过 3 个,使用 `aliases: [name1, name2]` 数组格式配置。所有别名共享同一个连接池。

### Q7: @DataSourceOn 和 @Qualifier 可以混用吗?

A: 可以。`@DataSourceOn` 指定类的默认数据源,`@Qualifier` 可以覆盖特定字段的数据源。优先级:`@Qualifier` > `@DataSourceOn` > 字段名匹配(@Resource) > `@Primary`。

### Q8: @DataSourceOn 如何处理数据源不存在的情况?

A: 默认严格模式(`strict=true`)会在启动时抛出异常。设置 `strict=false` 可以在数据源不存在时自动降级到 default 数据源。

### Q9: 什么场景推荐使用 @DataSourceOn?

A: 推荐在一个类主要使用一个数据源的场景使用 `@DataSourceOn`,可以大幅简化代码,提高可读性。

### Q10: 如何配置 MyBatis Plus 的 Mapper?

A: 使用 @MapperScan 指定 Mapper 包路径和对应的 SqlSessionFactory:

```java
@Configuration
@MapperScan(
    basePackages = "com.example.mapper.business",
    sqlSessionFactoryRef = "businessSqlSessionFactory"
)
public class BusinessMapperConfig {
}
```

### Q11: Druid 监控页面如何访问?

A: 配置 `spring.datasource.druid.stat-view-servlet.enabled=true`,然后访问 `/druid/` 路径。

### Q12: 如何处理分布式事务?

A: 推荐使用 Seata 等分布式事务框架。本组件主要解决单应用多数据源场景,不处理分布式事务。

### Q13: 字段名匹配时,为什么推荐使用 @Resource 而不是 @Autowired?

A: `@Resource` 优先按名称匹配,可靠绕过 `@Primary`; `@Autowired` 优先按类型匹配,会被 `@Primary` 影响。字段名匹配推荐使用 `@Resource`。

### Q14: 如何动态添加或移除数据源?

A: 使用 `MultiDataSourceManager` 的动态管理 API:

```java
@Autowired
private MultiDataSourceManager manager;

// 动态添加数据源
DataSourceProperties newConfig = new DataSourceProperties();
newConfig.setUrl("jdbc:postgresql://localhost:5432/newdb");
newConfig.setUsername("postgres");
newConfig.setPassword("password");
manager.addDataSource("newSource", newConfig);

// 动态移除数据源
manager.removeDatasource("oldSource");
```

**注意**: 动态添加的数据源不会自动标记为 `@Primary`，需要显式使用 `@Qualifier` 或 `@DataSourceOn` 注入。

### Q15: 为什么会报错 "more than one 'primary' bean found"? ⚠️

A: 这个错误表示 Spring 容器中存在**多个**标记为 `@Primary` 的 DataSource bean，导致依赖注入冲突。常见原因:

#### **原因 1: Spring Boot 自动配置冲突** (最常见)

如果你同时配置了:
1. `spring.datasource.*` (Spring Boot 原生配置)
2. `ldx2t.commons.datasource.*` (我们的 SDK 配置)

Spring Boot 会自动创建一个 `@Primary` 的 `dataSource` bean，与我们 SDK 创建的 @Primary bean 冲突。

**错误示例:**
```yaml
spring:
  datasource:  # ← Spring Boot 会创建 @Primary dataSource
    url: jdbc:postgresql://localhost:5432/maindb
    username: postgres
    password: postgres

ldx2t:
  commons:
    datasource:  # ← SDK 也会创建 @Primary defaultDataSource
      enabled: true
      datasources:
        default: ...
```

**解决方法**: 排除 Spring Boot 的 DataSource 自动配置:

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration

ldx2t:
  commons:
    datasource:
      enabled: true
      datasources:
        default:
          url: jdbc:postgresql://localhost:5432/maindb
          username: postgres
          password: postgres
          driver-class-name: org.postgresql.Driver
```

或者在启动类上使用注解:
```java
@SpringBootApplication(exclude = {
    DataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class
})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

#### **原因 2: Alias 配置冲突** ⚠️

**错误配置示例:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:  # ← 自动成为 @Primary
          url: jdbc:postgresql://localhost:5432/maindb
          ...
        business:
          aliases: [default, primary]  # ❌ 错误! "default" 导致 business 也成为 @Primary
          url: jdbc:postgresql://localhost:5432/businessdb
          ...
```

**为什么会冲突?**
- `defaultDataSource` → @Primary (因为名称为 "default")
- `businessDataSource` → @Primary (因为 alias 包含 "default")
- 结果: **两个 @Primary bean 冲突!**

**系统会自动检测并抛出清晰的错误:**
```
❌ Alias 冲突检测失败!
数据源 'business' 的 alias 'default' 与 primary 数据源名称冲突。
这会导致多个 @Primary bean，Spring 容器启动失败。

修复方法:
  1. 移除 'business' 数据源的 alias 'default'
  2. 或者将 'business' 数据源重命名为其他名称
```

**正确配置:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          url: jdbc:postgresql://localhost:5432/maindb
          ...
        business:
          aliases: [biz, order]  # ✅ 正确! 不包含 "default"
          url: jdbc:postgresql://localhost:5432/businessdb
          ...
```

#### **原因 3: 手动定义了 @Primary DataSource Bean**

检查你的 `@Configuration` 类中是否手动定义了:

```java
@Configuration
public class DataSourceConfig {
    @Bean
    @Primary  // ← 与 SDK 的 @Primary 冲突
    public DataSource dataSource() {
        return new DruidDataSource();
    }
}
```

**解决方法**: 移除手动定义，完全使用 SDK 管理数据源。

#### **快速诊断步骤:**

1. **检查是否同时配置了 `spring.datasource.*` 和 `ldx2t.commons.datasource.*`**
   - 如果是 → 添加 `spring.autoconfigure.exclude`

2. **检查 aliases 配置**
   - 搜索 YAML 中的 `aliases:`
   - 确保没有 alias 包含 "default" 或其他数据源的主名称

3. **检查是否有手动 @Bean @Primary**
   - 全局搜索 `@Primary` 注解
   - 移除手动定义的 DataSource bean

4. **查看启动日志**
   - 寻找 `Primary datasource auto-selected:` 日志
   - 确认只有一个数据源被选为 primary

## 10. 注意事项

- 生产环境【强制】配置数据库密码
- 多数据源场景【强制】明确指定事务管理器
- 使用 finally 块确保连接释放
- 合理设置连接池参数,避免连接泄漏
- 开启 Druid 监控和 SQL 防火墙
- 别名配置虽然方便,但不应过度使用,避免造成混淆
- @MapperScan 必须指定 sqlSessionFactoryRef

## 11. 与 Redis 注入器对比

| 特性 | Redis 注入器 | Datasource 注入器 |
|------|------------|------------------|
| 连接池 | Lettuce | Druid |
| 核心组件 | RedisTemplate、RedissonClient | DataSource、SqlSessionTemplate、TransactionManager |
| 主要用途 | 缓存、分布式锁 | 数据持久化、事务管理 |
| 事务支持 | Redis 事务(较弱) | 完整的 ACID 事务 |
| 注入方式 | @RedisOn | @DataSourceOn |
| 配置方式 | 完全一致 | 完全一致 |
| 命名规则 | 完全一致 | 完全一致 |
| 注入优先级 | 完全一致 | 完全一致 |

## 12. 版本历史

### v2.0 (当前版本)

**主要改进**:
- ✅ 优化注入方式说明,增加优先级规则
- ✅ 别名配置从逗号分隔改为 `aliases` 数组格式
- ✅ 增加 @Resource 注解使用建议
- ✅ 增加应用场景快速配置示例
- ✅ 优化最佳实践建议
- ✅ 统一 Redis 和 Datasource 的设计模式

### v1.0

**初始版本**:
- 基础多数据源支持
- 逗号分隔的别名配置
- 基本注入方式

## 13. 技术支持

如遇到问题,请提供以下信息:

- Spring Boot 版本
- MySQL/PostgreSQL/Oracle 版本
- Druid 版本
- MyBatis Plus 版本
- 完整配置文件
- 错误日志
- 复现步骤

联系开发团队获取技术支持。
