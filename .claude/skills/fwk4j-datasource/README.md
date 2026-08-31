# framework4j-datasource

> 多 DataSource 注入器：Druid + MyBatis Plus + `@DataSourceOn` 注解（类级 / 字段级自动路由）。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | `MultiDataSourceManager`（多数据源管理）/ `@DataSourceOn("name")` 注解处理器（类级 / 字段级）/ 动态添加 / 移除数据源 / 配合 `framework4j-sql-tracing` 注入 trace_id |
| 配置前缀 | `framework4j.datasource.*` |
| 必需依赖 | `spring-boot-starter-jdbc`、`druid-spring-boot-starter`、`mybatis-plus-spring-boot3-starter` |
| 可选依赖 | `framework4j-sql-tracing`（推荐一起用）、`framework4j-api`、`postgresql`（驱动） |
| 在 SDK 中的位置 | 数据访问层，独立于 `redis` / `accesstoken` |

**核心原则**：一个 `@DataSourceOn` 注解解决多数据源切换，无需 `@MapperScan` 分包。BeanPostProcessor 在 Bean 初始化前注入正确的 `DataSource`。

## 2. 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-datasource</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- 推荐配合 sql-tracing -->
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-sql-tracing</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最小 application.yml

```yaml
spring:
  application:
    name: my-app

framework4j:
  datasource:
    enabled: true
    datasources:
      default:
        url: jdbc:mysql://localhost:3306/main
        username: root
        password: ${DB_PASSWORD}
        driver-class-name: com.mysql.cj.jdbc.Driver
      order:
        url: jdbc:mysql://localhost:3306/order
        username: root
        password: ${DB_PASSWORD}
        driver-class-name: com.mysql.cj.jdbc.Driver
      log:
        url: jdbc:mysql://localhost:3306/log
        username: root
        password: ${DB_PASSWORD}
        driver-class-name: com.mysql.cj.jdbc.Driver
```

### 最小代码示例

```java
// 类级：整个 Service 用 order 数据源
@Service
@DataSourceOn("order")
public class OrderService {
    @Resource
    private OrderMapper orderMapper;  // 自动走 order 数据源
    
    public OrderDO find(Long id) {
        return orderMapper.selectById(id);
    }
}

// 字段级：单字段切换
@Service
public class LogService {
    @DataSourceOn("log")
    private JdbcTemplate logJdbc;
    
    public void write(String msg) {
        logJdbc.update("INSERT INTO logs (msg) VALUES (?)", msg);
    }
}
```

## 3. 配置参考

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.datasource.enabled` | `boolean` | `false` | 是否启用本模块（opt-in） |
| `framework4j.datasource.primary` | `String` | `default` | 默认数据源名 |
| `framework4j.datasource.datasources` | `Map<String, DataSourceProperties>` | — | 多数据源配置 |
| `framework4j.datasource.datasources.<name>.url` | `String` | 必填 | JDBC URL |
| `framework4j.datasource.datasources.<name>.username` | `String` | 必填 | 用户名 |
| `framework4j.datasource.datasources.<name>.password` | `String` | 必填 | 密码（环境变量） |
| `framework4j.datasource.datasources.<name>.driver-class-name` | `String` | 自动推断 | JDBC 驱动类 |
| `framework4j.datasource.datasources.<name>.druid.initial-size` | `int` | `5` | 初始连接数 |
| `framework4j.datasource.datasources.<name>.druid.max-active` | `int` | `20` | 最大连接数 |
| `framework4j.datasource.datasources.<name>.druid.max-wait` | `long` | `5000` | 获取连接超时（ms） |
| `framework4j.datasource.datasources.<name>.aliases` | `List<String>` | — | 别名（同一数据源多名字） |
| `framework4j.datasource.datasources.<name>.sql-tracing.*` | — | — | 见 `framework4j-sql-tracing` |

## 4. API 参考

### `@DataSourceOn`（注解）

```java
@Target({TYPE, FIELD})
@Retention(RUNTIME)
public @interface DataSourceOn {
    String value();              // 数据源名
    boolean strict() default true;  // true: 不存在抛异常; false: 回退 default
}
```

**类级**：类中所有 `JdbcTemplate` / `DataSource` / `Mapper` 字段自动注入指定数据源。
**字段级**：仅该字段注入指定数据源（优先于类级）。

### `MultiDataSourceManager`

```java
public class MultiDataSourceManager {
    public DataSource getDataSource(String name);
    public DataSource getDefaultDataSource();
    public boolean containsDatasource(String name);
    public List<String> getAllDatasourceNames();
    
    // 动态添加 / 移除（运行时）
    public void addDataSource(DataSourceProperties config);
    public void removeDatasource(String name);
    
    // 健康检查
    public boolean checkHealth(String name);
}
```

### `DataSourceOnBeanPostProcessor`

`BeanPostProcessor`，扫描带 `@DataSourceOn` 的 Bean，在 `postProcessBeforeInitialization` 阶段注入正确的 `DataSource` 实例。`strict=false` 时回退到 `default` 数据源。

### `MyBatisPlusConfig`

自动注册：
- `MybatisPlusInterceptor`（分页 + 乐观锁）
- `SqlSessionFactory` 按 `@DataSourceOn` 路由
- `MapperScannerConfigurer`（扫描 `fun.commons.framework4j.*.mapper`）

## 5. 示例

### 5.1 读写分离

```yaml
framework4j:
  datasource:
    datasources:
      default:    # 主库（写）
        url: jdbc:mysql://master:3306/mydb
      read:       # 从库（读）
        url: jdbc:mysql://slave:3306/mydb
```

```java
@Service
public class UserService {
    @Resource
    private UserMapper userMapper;       // 默认走 default（主库）
    
    @DataSourceOn("read")
    private UserMapper userReadMapper;   // 走从库
    
    @Transactional
    public void createUser(UserDO user) {
        userMapper.insert(user);          // 主库
    }
    
    public UserDO findUser(Long id) {
        return userReadMapper.selectById(id);  // 从库
    }
}
```

### 5.2 动态添加数据源（多租户）

```java
@Service
public class TenantDataSourceService {
    @Resource
    private MultiDataSourceManager manager;
    
    public void registerTenant(String tenantId, String jdbcUrl) {
        DataSourceProperties config = new DataSourceProperties();
        config.setName(tenantId);
        config.setUrl(jdbcUrl);
        config.setUsername("root");
        config.setPassword(System.getenv("TENANT_DB_PWD"));
        manager.addDataSource(config);
        // 失败时自动回滚（已实现原子性）
    }
    
    public void removeTenant(String tenantId) {
        manager.removeDatasource(tenantId);
    }
}
```

### 5.3 别名 + `strict=false`

```yaml
framework4j:
  datasource:
    datasources:
      order:
        aliases: [order-read, order-replica]  # 别名
        url: jdbc:mysql://master:3306/order
      cache:
        url: jdbc:mysql://cache:3306/cache
```

```java
@Service
@DataSourceOn(value = "order-replica", strict = false)  // 别名 + 回退 default
public class OrderService { ... }
```

## 6. 错误码

| Code | 名称 | 触发场景 |
|---|---|---|
| `10900` | `INTERNAL_ERROR` | 数据源初始化失败（连接超时 / 密码错） |
| `10400` | `NOT_FOUND` | `@DataSourceOn("xxx")` 但 `xxx` 不存在且 `strict=true` |

## 7. FAQ

**Q1：`@DataSourceOn` 和 Spring `@Qualifier` 区别？**
A：`@DataSourceOn` 是 SDK 自定义注解，由 `DataSourceOnBeanPostProcessor` 处理，支持类级 + 字段级 + `strict` 回退。`@Qualifier` 是 Spring 原生，仅字段级，无回退机制。建议用 `@DataSourceOn`。

**Q2：多数据源下事务怎么处理？**
A：Spring 默认单数据源事务管理器。多数据源需用 `@Transactional(transactionManager = "xxxTransactionManager")`。或引入 `seata` 分布式事务（SDK 不内置）。

**Q3：动态添加数据源失败会回滚吗？**
A：会。`addDataSource` 内部用原子注册 + 失败回滚（销毁连接工厂 + 删除已注册 Bean）。

**Q4：MyBatis Plus 的 `@TableName` / `@TableId` 还能用吗？**
A：能。本模块只负责数据源切换，不干预 MyBatis Plus 的实体注解。

**Q5：`druid` 监控页能看每个数据源吗？**
A：能。`/druid/datasource.html` 列出所有数据源。每数据源独立配置 `stat-view-servlet` 白名单 + 凭证（见 mc-java-spec §4.4.4）。

## MyBatis Plus 内置插件

framework4j 内置 MyBatis Plus 常用拦截器，yml 开关控制，零代码。

### 默认策略

| 插件 | 默认 | 理由 |
|---|---|---|
| **分页**（PaginationInnerInterceptor） | ✅ 加载 | 只在传 `IPage` 参数时激活，对无分页透明 |
| **防全表更新**（BlockAttackInnerInterceptor） | ✅ 加载 | 安全护栏，防 UPDATE/DELETE 不带 WHERE |
| **乐观锁**（OptimisticLockerInnerInterceptor） | ❌ 不加载 | 需 Entity `@Version` 字段 |
| **多租户**（TenantLineInnerInterceptor） | ❌ 不加载 | 需租户上下文基础设施 |

### yml 配置

```yaml
framework4j:
  datasource:
    datasources:
      default:
        url: jdbc:postgresql://localhost/mydb
        mybatis-plus-plugins:
          enabled: true              # 总开关（默认 true）
          pagination: true           # 分页（默认 true）
          db-type: POSTGRE_SQL       # 指定 DbType（默认自动检测）
          block-attack: true         # 防全表更新（默认 true）
          optimistic-lock: true      # 开启乐观锁（默认 false）
          data-permission: true      # 开启多租户（默认 false）
          tenant-column: tenant_id
          tenant-ignore-tables: [sys_config, sys_dict]
```

### 关闭内置插件（全自定义）

```yaml
framework4j:
  datasource:
    datasources:
      default:
        mybatis-plus-plugins:
          enabled: false  # 关闭 SDK 默认，用自定义 Bean
```

### 用户自定义覆盖

```java
@Configuration
public class MyInterceptorConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // SDK 的默认 Bean 自动退让（@ConditionalOnMissingBean）
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        // 加自定义拦截器
        interceptor.addInnerInterceptor(new MyCustomInterceptor());
        return interceptor;
    }
}
```

### 多数据源不同 DbType

多数据源共用一个 `MybatisPlusInterceptor`（MyBatis Plus 设计）。不同 DbType 时需自定义：

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    // 用不指定 DbType 的分页（自动检测每个数据源）
    PaginationInnerInterceptor page = new PaginationInnerInterceptor();
    interceptor.addInnerInterceptor(page);
    return interceptor;
}
```

### 多租户方案

```java
@Bean
public TenantLineHandler tenantLineHandler() {
    return new TenantLineHandler() {
        @Override
        public Expression getTenantId() {
            Long tenantId = TenantContext.get(); // 从 ThreadLocal / TokenContext 取
            return new LongValue(tenantId);
        }
        @Override
        public String getTenantIdColumn() { return "tenant_id"; }
        @Override
        public boolean ignoreTable(String tableName) {
            return "sys_config".equals(tableName); // 忽略全局配置表
        }
    };
}
```

## 📚 文档导航

### 按「我想做什么」找文档

| 我想… | 看这个文档 |
|---|---|
| 快速上手多数据源 | 本 README（当前页） |
| 了解核心特性与场景 | [特性与场景](./DESIGN-FEATURES.md) |
| 查配置项含义 | [配置说明](./DESIGN-CONFIG.md) |
| 学注入方式（@DataSourceOn 等） | [使用指南](./DESIGN-USAGE.md) |
| 连接池调优 / MyBatis Plus 最佳实践 | [最佳实践](./DESIGN-BEST-PRACTICES.md) |
| 排查常见问题 / Druid 监控 | [FAQ 与监控](./DESIGN-FAQ.md) |
| 看测试覆盖 | [测试文档](./TESTING.md) |
| SQL trace_id 追踪 | `framework4j-sql-tracing/README.md` |

### 按角色找文档

| 角色 | 推荐阅读 |
|---|---|
| 新人 | 本 README → DESIGN-FEATURES → DESIGN-USAGE |
| 架构师 | DESIGN-FEATURES → DESIGN-CONFIG → DESIGN-BEST-PRACTICES |
| 运维 | DESIGN-CONFIG → DESIGN-BEST-PRACTICES → DESIGN-FAQ |

## ⚠️ 已知依赖冲突与解决方案

### jsqlparser 版本冲突（v1.1.1 已修复）

**问题**：MyBatis-Plus 3.5.9+ 将分页拦截器拆到 `mybatis-plus-jsqlparser` 模块，该模块依赖 `jsqlparser:5.x`。如果 framework4j 硬传递此依赖，会覆盖旧版 MyBatis-Plus（≤3.5.8）需要的 `jsqlparser:4.x`，导致 `NoClassDefFoundError`。

**framework4j 的修复**（v1.1.2+）：`mybatis-plus-jsqlparser` 设为 `optional=true`，不传递给消费者。

**消费者使用内置插件**：

```xml
<!-- 需要用 framework4j 内置分页插件时，自行引入（版本与你的 mybatis-plus 对齐） -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-jsqlparser</artifactId>
    <version>${mybatis-plus.version}</version> <!-- 如 3.5.5 对应 jsqlparser 4.6 -->
</dependency>
```

**不用内置插件**（自己写 `@Bean MybatisPlusInterceptor`）→ 无需引入。

**多版本共存检查**：framework4j 提供兼容性测试模块 `framework4j-compat-test`，自动验证旧版生态依赖不冲突。

### 通用依赖冲突排查

```bash
# 查看实际 classpath 中的依赖树
mvn dependency:tree | grep jsqlparser

# Maven Enforcer 自动检测冲突
mvn enforcer:enforce -Drules=dependencyConvergence
```
