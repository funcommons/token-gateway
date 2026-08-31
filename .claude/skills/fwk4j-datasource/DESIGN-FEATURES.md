← [返回 README](./README.md)

# 多 Datasource 数据源注入器产品文档 v2

## 1. 功能概述

多 Datasource 数据源注入器是 LDX2T Commons SDK 的核心组件之一,为企业应用提供多数据库数据源管理和注入能力。该组件解决了微服务架构中应用需要连接多个数据库的场景需求。

### 1.1 核心价值

在企业级微服务架构中,不同业务场景通常需要使用不同的数据库实例:

- **业务数据**:使用主业务数据库存储核心业务数据
- **日志数据**:使用独立数据库存储操作日志和审计数据
- **报表数据**:使用专用数据库进行数据分析和报表生成
- **配置数据**:使用独立数据库存储系统配置信息
- **多租户隔离**:为不同租户分配独立的数据库实例

传统方案需要在每个应用中手动配置多个数据源,代码重复且容易出错。多 Datasource 数据源注入器提供统一的配置和管理方案,简化开发工作。

## 2. 核心特性

### 2.1 多数据源支持

- 支持在单个应用中配置多个数据库数据源
- 每个数据源独立命名,便于识别和使用
- 支持配置不同的连接参数(url、username、password、driverClassName)
- 每个数据源独立的 Druid 连接池配置

### 2.2 完整组件注入

同时注入多个核心组件:

#### DataSource(Druid)

- 高性能数据库连接池
- 内置监控和统计功能
- 支持 SQL 防火墙
- 提供连接泄漏检测

#### SqlSessionFactory(MyBatis Plus)

- MyBatis 核心工厂类
- 支持自定义配置
- 与 MyBatis Plus 无缝集成
- 支持类型处理器和插件

#### SqlSessionTemplate(MyBatis Plus)

- 线程安全的 SqlSession 实现
- 自动管理 SqlSession 生命周期
- 支持批量操作
- 与 Spring 事务无缝集成

#### PlatformTransactionManager(Spring)

- Spring 事务管理器
- 支持声明式事务(@Transactional)
- 支持编程式事务
- 支持事务传播和隔离级别

#### JdbcTemplate(Spring)

- Spring 原生 JDBC 模板类
- 简化 JDBC 操作
- 自动资源管理
- 支持命名参数和批量操作
- 适用于简单 SQL 查询和更新

### 2.3 灵活注入方式

支持多种注入方式(从简单到高级):

#### 方式一:默认注入(@Primary)

`default` 数据源自动标记为 **@Primary**,不使用 `@Qualifier` 时默认注入:

```java
@Autowired
private DataSource dataSource;              // 自动注入 defaultDataSource
@Autowired
private SqlSessionTemplate sqlSessionTemplate;  // 自动注入 defaultSqlSessionTemplate
@Autowired
private PlatformTransactionManager transactionManager;  // 自动注入 defaultTransactionManager
@Autowired
private JdbcTemplate jdbcTemplate;          // 自动注入 defaultJdbcTemplate
```

**注入优先级**: @Qualifier > @DataSourceOn > 字段名匹配(@Resource) > @Primary > 抛异常

#### 方式二:@DataSourceOn 类级别注解(推荐)

类级别指定数据源,类中所有字段自动注入对应数据源,无需每个字段加 `@Qualifier`:

```java
@Service
@DataSourceOn("business")
public class OrderService {
    @Autowired
    private DataSource dataSource;  // 自动注入 businessDataSource
    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;  // 自动注入 businessSqlSessionTemplate
    @Autowired
    private PlatformTransactionManager transactionManager;  // 自动注入 businessTransactionManager
    @Autowired
    private JdbcTemplate jdbcTemplate;  // 自动注入 businessJdbcTemplate
}
```

**混合使用**: 可用 `@Qualifier` 覆盖特定字段

```java
@Service
@DataSourceOn("business")  // 默认 business
public class MixedService {
    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;  // business

    @Autowired
    @Qualifier("logSqlSessionTemplate")  // 覆盖为 log
    private SqlSessionTemplate logTemplate;
}
```

**参数**: `value`=数据源名, `strict`=严格模式(默认true,数据源不存在时抛异常)

#### 方式三:@Qualifier 注解注入

显式指定Bean名称,精确控制注入:

```java
@Autowired
@Qualifier("businessDataSource")
private DataSource businessDataSource;

@Autowired
@Qualifier("logSqlSessionTemplate")
private SqlSessionTemplate logTemplate;
```

**适用**: 同一类使用多个数据源、明确性要求高

#### 方式四:字段名匹配注入(@Resource)

字段名与Bean名完全匹配时自动注入。**推荐用 `@Resource`**(优先按名称),可靠绕过 `@Primary`:

```java
import jakarta.annotation.Resource;

@Resource  // 优先按名称,可靠绕过 @Primary
private DataSource businessDataSource;  // 注入 businessDataSource

@Resource
private SqlSessionTemplate logSqlSessionTemplate;  // 注入 logSqlSessionTemplate

@Autowired  // default 是 @Primary,用 @Autowired 也可以
private DataSource defaultDataSource;
```

**注解对比**: `@Resource`(JSR-250)先按名称后按类型, `@Autowired`(Spring)先按类型后按名称

#### 方式五:MultiDataSourceManager 统一管理

运行时动态获取数据源,适用于多租户/动态切换场景:

```java
@Autowired
private MultiDataSourceManager dataSourceManager;

// 动态获取
DataSource ds = dataSourceManager.getDataSource("business");
SqlSessionTemplate template = dataSourceManager.getSqlSessionTemplate("log");

// 快捷方法
DataSource defaultDs = dataSourceManager.getDefaultDataSource();
SqlSessionTemplate defaultTemplate = dataSourceManager.getDefaultSqlSessionTemplate();
```

**常用API**: `getDataSource(name)`, `getSqlSessionTemplate(name)`, `getSqlSessionFactory(name)`, `getTransactionManager(name)`, `getJdbcTemplate(name)`, `getDefaultXXX()`, `getAllDatasourceNames()`, `containsDatasource(name)`, `checkHealth(name)`

### 2.4 Primary 数据源自动选择

系统自动选择 Primary 数据源,无需手动配置。选择优先级:

1. **"default" 数据源**: 如果配置中存在名为 `default` 的数据源,自动将其标记为 `@Primary`
2. **首个配置的数据源**: 如果不存在 `default` 数据源,使用配置文件中第一个数据源作为 `@Primary`

**示例 1 - 使用 "default" 数据源(推荐)**:
```yaml
ldx2t:
  commons:
    datasource:
      enabled: true
      datasources:
        default:    # ✅ 自动成为 @Primary
          url: jdbc:postgresql://localhost:5432/maindb
          username: postgres
          password: password
          driver-class-name: org.postgresql.Driver
        business:   # 普通数据源
          url: jdbc:postgresql://localhost:5432/businessdb
          username: postgres
          password: password
          driver-class-name: org.postgresql.Driver
```

**示例 2 - 无 "default" 时使用首个配置**:
```yaml
ldx2t:
  commons:
    datasource:
      enabled: true
      datasources:
        main:       # ✅ 第一个配置,自动成为 @Primary
          url: jdbc:postgresql://localhost:5432/maindb
          username: postgres
          password: password
          driver-class-name: org.postgresql.Driver
        business:   # 普通数据源
          url: jdbc:postgresql://localhost:5432/businessdb
          username: postgres
          password: password
          driver-class-name: org.postgresql.Driver
```

**旧配置方式已移除**:
```yaml
# ❌ 不再支持 - 配置会被忽略
ldx2t:
  commons:
    datasource:
      primary:              # ← 此配置已完全移除
        datasource: xxx     # ← 不再识别此字段
```

如果您的配置中存在 `primary.datasource`,请删除此配置。系统将自动选择 Primary 数据源。

### 2.5 命名空间隔离

- 基于数据源名称自动生成 Bean 名称
- 避免多个数据源之间的命名冲突
- 命名规则:`{datasourceName}DataSource`、`{datasourceName}SqlSessionFactory`、`{datasourceName}SqlSessionTemplate`、`{datasourceName}TransactionManager`、`{datasourceName}JdbcTemplate`

### 2.6 动态配置

- 支持运行时动态添加新的数据源
- 支持动态修改数据源配置
- 支持动态移除数据源
- 提供数据源健康检查功能

### 2.7 连接池管理(Druid)

- 每个数据源独立的 Druid 连接池
- 支持自定义连接池参数(最大连接数、最小空闲连接数等)
- 内置监控统计功能
- SQL 防火墙和防注入
- 连接泄漏检测
- 自动连接回收和超时处理

### 2.8 MyBatis Plus 集成

- 完全兼容 MyBatis Plus 所有特性
- 支持 BaseMapper 自动注入
- 支持分页插件
- 支持代码生成器
- 支持多租户插件
- 支持动态表名

### 2.9 统一规范

- 完全遵循企业开发规范
- 基于 Spring Boot 3.2 和 Java 17
- 使用 @ConfigurationProperties 进行配置管理
- 支持 application.yml 配置

## 3. 应用场景

**典型场景配置**:

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        # 场景1: 业务与日志分离
        business:
          url: jdbc:mysql://db-business.com:3306/business_db
        log:
          url: jdbc:mysql://db-log.com:3306/log_db

        # 场景2: 读写分离
        master:
          url: jdbc:mysql://db-master.com:3306/app_db
        slave:
          url: jdbc:mysql://db-slave.com:3306/app_db

        # 场景3: 多租户
        tenant-a:
          url: jdbc:mysql://db-tenant-a.com:3306/tenant_a_db
          username: tenant_a_user
          password: pwd_a
        tenant-b:
          url: jdbc:mysql://db-tenant-b.com:3306/tenant_b_db
          username: tenant_b_user
          password: pwd_b

        # 场景4: 业务与报表分离
        business:
          url: jdbc:mysql://db-business.com:3306/business_db
          druid.max-active: 20
        report:
          url: jdbc:mysql://db-report.com:3306/report_db
          druid.max-active: 10
```

**使用示例**:

```java
// 场景1: 业务与日志分离
@Transactional("businessTransactionManager")
public void createOrder(Order order) {
    businessTemplate.insert("OrderMapper.insert", order);
    logTemplate.insert("AuditLogMapper.insert", auditLog);
}

// 场景2: 读写分离
public void saveUser(User user) {
    masterTemplate.insert("UserMapper.insert", user);  // 写
}
public User getUser(Long id) {
    return slaveTemplate.selectOne("UserMapper.selectById", id);  // 读
}

// 场景3: 多租户
SqlSessionTemplate template = dataSourceManager.getSqlSessionTemplate("tenant-" + tenantId);
template.insert("OrderMapper.insert", order);

// 场景4: 业务与报表分离
businessTemplate.insert("OrderMapper.insert", order);  // 业务写入
Map<String, Object> report = reportTemplate.selectOne("ReportMapper.salesSummary", params);  // 报表查询

// 场景5: 使用 JdbcTemplate 进行简单查询
@Service
@DataSourceOn("business")
public class ReportService {
    @Autowired
    private JdbcTemplate jdbcTemplate;  // 自动注入 businessJdbcTemplate

    public int getOrderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }

    public List<Order> getRecentOrders() {
        return jdbcTemplate.query("SELECT * FROM orders ORDER BY created_at DESC LIMIT 10",
            (rs, rowNum) -> {
                Order order = new Order();
                order.setId(rs.getLong("id"));
                order.setOrderNo(rs.getString("order_no"));
                return order;
            });
    }
}
```

