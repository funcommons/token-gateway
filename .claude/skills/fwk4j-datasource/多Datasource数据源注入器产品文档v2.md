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

## 4. 配置说明

### 4.1 基础配置

```yaml
ldx2t:
  commons:
    datasource:
      enabled: true  # 是否启用多数据源注入器
      datasources:
        # 数据源名称(必须唯一)
        default:
          url: jdbc:mysql://localhost:3306/app_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
          username: root
          password: your_password
          driver-class-name: com.mysql.cj.jdbc.Driver  # 驱动类名,默认自动检测
```

### 4.2 多名同源(别名)配置

支持为同一个数据源配置多个名称(别名),使用 **`aliases`** 字段指定别名列表。

#### 语法格式

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        business:  # 主名称
          aliases: [order, product]  # 别名列表
          url: jdbc:mysql://localhost:3306/business_db
          username: root
          password: password
```

#### 配置示例

```yaml
ldx2t:
  commons:
    datasource:
      enabled: true
      datasources:
        # 主名称:default,别名:main
        default:
          aliases: [main]  # 别名列表
          url: jdbc:mysql://localhost:3306/app_db
          username: root
          password: root_pass
          druid:
            initial-size: 5
            min-idle: 5
            max-active: 20

        # 主名称:business,别名:order、product
        business:
          aliases: [order, product]  # 多个别名
          url: jdbc:mysql://db-business.example.com:3306/business_db
          username: business_user
          password: business_password
          druid:
            initial-size: 10
            min-idle: 10
            max-active: 50
```

#### Bean 命名规则

每个名称都会注册独立的 Bean,但它们**共享同一个数据源连接池**。

**示例**:配置 `default: aliases: [main]` 会生成以下 Bean:

| Bean 名称 | 类型 | 说明 |
|-----------|------|------|
| defaultDataSource | DataSource | 主名称 |
| mainDataSource | DataSource | 别名 |
| defaultSqlSessionFactory | SqlSessionFactory | 主名称 |
| mainSqlSessionFactory | SqlSessionFactory | 别名 |
| defaultSqlSessionTemplate | SqlSessionTemplate | 主名称 |
| mainSqlSessionTemplate | SqlSessionTemplate | 别名 |
| defaultTransactionManager | PlatformTransactionManager | 主名称 |
| mainTransactionManager | PlatformTransactionManager | 别名 |

**@Primary 规则**:主名称(`default`)的 Bean 会被标记为 `@Primary`。

#### 使用示例

```java
@Service
public class AliasService {

    // 使用主名称注入
    @Autowired
    @Qualifier("defaultSqlSessionTemplate")
    private SqlSessionTemplate defaultTemplate;

    // 使用别名注入
    @Autowired
    @Qualifier("mainSqlSessionTemplate")
    private SqlSessionTemplate mainTemplate;

    // 不使用 @Qualifier,注入 default(@Primary)
    @Autowired
    private SqlSessionTemplate sqlSessionTemplate;

    public void testAliases() {
        // 三个变量都指向同一个数据源
        defaultTemplate.insert("Mapper.insert", data);
        mainTemplate.insert("Mapper.insert", data);
        sqlSessionTemplate.insert("Mapper.insert", data);

        // 验证是同一个实例
        assert defaultTemplate == mainTemplate;
        assert defaultTemplate == sqlSessionTemplate;
    }
}
```

```java
@Service
public class OrderService {

    // 使用主名称 business
    @Autowired
    @Qualifier("businessSqlSessionTemplate")
    private SqlSessionTemplate businessTemplate;

    // 使用别名 order(更语义化)
    @Autowired
    @Qualifier("orderSqlSessionTemplate")
    private SqlSessionTemplate orderTemplate;

    // 使用别名 product
    @Autowired
    @Qualifier("productSqlSessionTemplate")
    private SqlSessionTemplate productTemplate;

    @Transactional("orderTransactionManager")
    public void saveOrder(Order order) {
        // 三个变量指向同一个数据源
        // 使用不同名称可以增强代码可读性
        orderTemplate.insert("OrderMapper.insert", order);
    }

    public void saveProduct(Product product) {
        // 语义化命名,更清晰
        productTemplate.insert("ProductMapper.insert", product);
    }
}
```

#### 典型应用场景

**场景一:语义化命名**

```yaml
datasources:
  business:
    aliases: [order, product, inventory]
    url: jdbc:mysql://db-business.example.com:3306/business_db
```

不同模块可以使用不同的语义化名称,提高代码可读性:
- 订单模块使用 `orderSqlSessionTemplate`
- 商品模块使用 `productSqlSessionTemplate`
- 库存模块使用 `inventorySqlSessionTemplate`

**场景二:平滑迁移和向后兼容**

```yaml
datasources:
  newName:
    aliases: [oldName]
    url: jdbc:mysql://db-new.example.com:3306/app_db
```

在重构时保持向后兼容:
- 新代码使用 `newNameSqlSessionTemplate`
- 旧代码仍可使用 `oldNameSqlSessionTemplate`
- 逐步迁移,避免大规模改动

**场景三:多团队协作**

```yaml
datasources:
  shared:
    aliases: [team-a, team-b]
    url: jdbc:mysql://db-shared.example.com:3306/shared_db
```

不同团队使用统一资源但保持各自的命名习惯。

#### 注意事项

- 【推荐】别名不超过 3 个,避免混淆
- 【推荐】主名称使用最常用名称
- 【强制】别名数组格式: `aliases: [name1, name2]`
- 【提示】所有别名共享同一个连接池,不会增加资源消耗

### 4.3 Druid 连接池配置

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          url: jdbc:mysql://localhost:3306/app_db
          username: root
          password: your_password
          druid:
            # 连接池配置
            initial-size: 5                  # 初始化连接数,默认 5
            min-idle: 5                      # 最小空闲连接数,默认 5
            max-active: 20                   # 最大活跃连接数,默认 20
            max-wait: 60000                  # 获取连接最大等待时间(毫秒),默认 60000

            # 连接检测配置
            test-on-borrow: false            # 申请连接时检测,默认 false
            test-on-return: false            # 归还连接时检测,默认 false
            test-while-idle: true            # 空闲时检测,默认 true
            validation-query: SELECT 1       # 检测查询 SQL
            validation-query-timeout: 3      # 检测查询超时时间(秒)

            # 连接回收配置
            time-between-eviction-runs-millis: 60000   # 检测间隔时间(毫秒),默认 60000
            min-evictable-idle-time-millis: 300000     # 连接最小生存时间(毫秒),默认 300000
            max-evictable-idle-time-millis: 900000     # 连接最大生存时间(毫秒),默认 900000

            # 连接泄漏检测
            remove-abandoned: true           # 是否开启连接泄漏检测,默认 false
            remove-abandoned-timeout: 180    # 连接泄漏超时时间(秒),默认 300
            log-abandoned: true              # 是否记录连接泄漏日志,默认 false

            # 监控统计配置
            filters: stat,wall,slf4j         # 过滤器:stat(监控统计)、wall(防火墙)、slf4j(日志)

            # 连接属性
            connection-properties: druid.stat.mergeSql=true;druid.stat.slowSqlMillis=5000

            # PSCache 配置(Oracle/PostgreSQL 建议开启)
            pool-prepared-statements: false  # 是否缓存 PreparedStatement,默认 false
            max-pool-prepared-statement-per-connection-size: 20  # 每个连接最大缓存数
```

### 4.4 MyBatis Plus 配置

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          url: jdbc:mysql://localhost:3306/app_db
          username: root
          password: your_password
          mybatis-plus:
            # Mapper XML 文件位置
            mapper-locations: classpath*:/mapper/**/*.xml

            # 实体类包路径
            type-aliases-package: com.example.entity

            # MyBatis 配置文件位置
            config-location: classpath:mybatis-config.xml

            # 全局配置
            global-config:
              db-config:
                id-type: AUTO                      # 主键类型:AUTO(数据库自增)、ASSIGN_ID(雪花算法)
                table-prefix: t_                   # 表名前缀
                logic-delete-field: deleted        # 逻辑删除字段名
                logic-delete-value: 1              # 逻辑删除值
                logic-not-delete-value: 0          # 逻辑未删除值

            # 配置项
            configuration:
              map-underscore-to-camel-case: true   # 下划线转驼峰
              cache-enabled: false                 # 是否开启二级缓存
              log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl  # 日志实现
```

### 4.5 完整配置示例

```yaml
spring:
  application:
    name: order-service

ldx2t:
  commons:
    datasource:
      enabled: true
      datasources:
        # 默认业务数据库
        default:
          url: jdbc:mysql://localhost:3306/app_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
          username: root
          password: root_pass
          driver-class-name: com.mysql.cj.jdbc.Driver
          druid:
            initial-size: 5
            min-idle: 5
            max-active: 20
            max-wait: 60000
            test-while-idle: true
            validation-query: SELECT 1
            filters: stat,wall,slf4j
          mybatis-plus:
            mapper-locations: classpath*:/mapper/**/*.xml
            type-aliases-package: com.example.entity
            configuration:
              map-underscore-to-camel-case: true
              log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl

        # 日志数据库
        log:
          url: jdbc:mysql://db-log.example.com:3306/log_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
          username: log_user
          password: log_pass
          druid:
            initial-size: 3
            min-idle: 3
            max-active: 10
            filters: stat,slf4j
          mybatis-plus:
            mapper-locations: classpath*:/mapper/log/**/*.xml

        # 报表数据库
        report:
          url: jdbc:mysql://db-report.example.com:3306/report_db?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
          username: report_user
          password: report_pass
          druid:
            initial-size: 2
            min-idle: 2
            max-active: 10
            max-wait: 120000
            filters: stat
          mybatis-plus:
            mapper-locations: classpath*:/mapper/report/**/*.xml
```

### 4.6 配置参数说明

| 参数 | 说明 | 默认值 |
|------|------|--------|
| url | JDBC URL | 无 |
| username | 数据库用户名 | 无 |
| password | 数据库密码 | 无 |
| driver-class-name | 驱动类名 | 自动检测 |
| druid.initial-size | 初始化连接数 | 5 |
| druid.min-idle | 最小空闲连接数 | 5 |
| druid.max-active | 最大活跃连接数 | 20 |
| druid.max-wait | 最大等待时间(毫秒) | 60000 |
| druid.test-while-idle | 空闲时检测连接 | true |
| druid.filters | Druid 过滤器 | stat,wall,slf4j |
| mybatis-plus.mapper-locations | Mapper XML 位置 | classpath*:/mapper/**/*.xml |
| mybatis-plus.type-aliases-package | 实体类包路径 | 无 |

## 5. 使用指南

### 5.1 Maven 依赖

```xml
<dependency>
    <groupId>com.ldx2t</groupId>
    <artifactId>ldx2t-commons-all</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- MySQL 驱动 -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Druid 连接池 -->
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>druid-spring-boot-3-starter</artifactId>
    <version>1.2.23</version>
</dependency>

<!-- MyBatis Plus -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>3.5.5</version>
</dependency>
```

### 5.2 基础使用

> 💡 注入方式详见第2.3节,此处仅展示动态管理用法。

#### 5.2.1 混合使用示例

展示如何在同一个服务中使用多种注入方式:

```java
@Service
public class MixedInjectionService {

    // 方式一:不使用 @Qualifier,注入 default(@Primary)
    @Autowired
    private DataSource dataSource;

    // 方式四:字段名匹配,注入 business(推荐使用 @Resource)
    @Resource
    private DataSource businessDataSource;

    // 方式三:@Qualifier 显式指定,注入 log
    @Autowired
    @Qualifier("logSqlSessionTemplate")
    private SqlSessionTemplate logTemplate;

    // 方式五:MultiDataSourceManager 动态获取
    @Autowired
    private MultiDataSourceManager dataSourceManager;

    public void processData(Long orderId) {
        // 使用 default DataSource
        try (Connection conn = dataSource.getConnection()) {
            // 执行操作
        }

        // 使用 business DataSource
        try (Connection conn = businessDataSource.getConnection()) {
            // 执行业务操作
        }

        // 使用 log SqlSessionTemplate
        logTemplate.insert("AuditLogMapper.insert", auditLog);

        // 动态获取其他数据源
        SqlSessionTemplate reportTemplate = dataSourceManager.getSqlSessionTemplate("report");
    }
}
```

#### 5.2.2 使用 MultiDataSourceManager

```java
@Autowired
private MultiDataSourceManager dataSourceManager;

// 动态获取指定数据源
DataSource ds = dataSourceManager.getDataSource("business");
SqlSessionTemplate template = dataSourceManager.getSqlSessionTemplate("log");
SqlSessionFactory factory = dataSourceManager.getSqlSessionFactory("business");
PlatformTransactionManager txManager = dataSourceManager.getTransactionManager("business");

// 快捷获取默认数据源
DataSource defaultDs = dataSourceManager.getDefaultDataSource();
SqlSessionTemplate defaultTemplate = dataSourceManager.getDefaultSqlSessionTemplate();

// 管理方法
List<String> names = dataSourceManager.getAllDatasourceNames();
boolean exists = dataSourceManager.containsDatasource("business");
boolean healthy = dataSourceManager.checkHealth("business");
```

### 5.3 高级使用

#### 5.3.1 动态添加/删除数据源

```java
// 添加数据源
DataSourceConfig config = DataSourceConfig.builder()
    .name("tenant-" + tenantId)
    .url("jdbc:mysql://db-host.com:3306/tenant_db")
    .username("tenant_user")
    .password("tenant_pass")
    .build();
dataSourceManager.addDataSource(config);

// 删除数据源
dataSourceManager.removeDatasource("tenant-123");
```

#### 5.3.2 健康检查

```java
@Scheduled(fixedRate = 60000)
public void checkHealth() {
    for (String name : dataSourceManager.getAllDatasourceNames()) {
        if (!dataSourceManager.checkHealth(name)) {
            // 告警处理
        }
    }
}
```

#### 5.3.3 声明式事务

```java
@Service
public class OrderService {

    @Autowired
    @Qualifier("businessSqlSessionTemplate")
    private SqlSessionTemplate businessTemplate;

    // 使用 business 数据源的事务管理器
    @Transactional("businessTransactionManager")
    public void createOrder(Order order) {
        businessTemplate.insert("OrderMapper.insert", order);

        // 如果发生异常,自动回滚
        if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("订单金额必须大于0");
        }
    }
}
```

#### 5.3.4 编程式事务

```java
@Service
public class OrderService {

    @Autowired
    @Qualifier("businessTransactionManager")
    private PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("businessSqlSessionTemplate")
    private SqlSessionTemplate sqlSessionTemplate;

    public void createOrder(Order order) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            sqlSessionTemplate.insert("OrderMapper.insert", order);
            transactionManager.commit(status);
        } catch (Exception e) {
            transactionManager.rollback(status);
            throw e;
        }
    }
}
```

#### 5.3.5 使用 MyBatis Plus BaseMapper

```java
// 定义 Mapper 接口
@Mapper
public interface UserMapper extends BaseMapper<User> {
    // 继承 BaseMapper 自动拥有 CRUD 方法
}

// 使用 @MapperScan 指定数据源
@Configuration
@MapperScan(
    basePackages = "com.example.mapper.business",
    sqlSessionFactoryRef = "businessSqlSessionFactory"
)
public class BusinessMapperConfig {
}

@Configuration
@MapperScan(
    basePackages = "com.example.mapper.log",
    sqlSessionFactoryRef = "logSqlSessionFactory"
)
public class LogMapperConfig {
}

// Service 中使用
@Service
public class UserService {

    @Autowired
    private UserMapper userMapper;  // 自动注入 business 数据源的 Mapper

    public void saveUser(User user) {
        userMapper.insert(user);
    }

    public User getUser(Long userId) {
        return userMapper.selectById(userId);
    }

    public List<User> listUsers() {
        return userMapper.selectList(null);
    }
}
```

## 6. Bean 命名规则

- DataSource:`{datasourceName}DataSource`
- SqlSessionFactory:`{datasourceName}SqlSessionFactory`
- SqlSessionTemplate:`{datasourceName}SqlSessionTemplate`
- PlatformTransactionManager:`{datasourceName}TransactionManager`

示例:
- `defaultDataSource`
- `defaultSqlSessionFactory`
- `defaultSqlSessionTemplate`
- `defaultTransactionManager`
- `businessDataSource`
- `businessSqlSessionFactory`
- `businessSqlSessionTemplate`
- `businessTransactionManager`

## 7. 最佳实践

### 7.1 命名规范

- **数据源名称**:使用小写字母和短横线,如 `default`、`business`、`log`
- **Bean 名称**:自动生成,遵循 `{datasourceName}[Type]` 格式
- **Mapper 接口**:放在不同包下,使用 @MapperScan 指定数据源
- **表命名**:遵循企业开发规范,使用下划线命名法

### 7.2 连接池配置建议

| 场景 | initial-size | min-idle | max-active |
|------|--------------|----------|-----------|
| 低并发(日志) | 3 | 3 | 10 |
| 中等并发(业务) | 5 | 5 | 20 |
| 高并发(核心业务) | 10 | 10 | 50 |
| 报表查询 | 2 | 2 | 10 |

### 7.3 客户端选择建议

| 场景 | 推荐组件 | 原因 |
|------|---------|------|
| 简单 CRUD | SqlSessionTemplate | 线程安全,与 Spring 集成好 |
| 复杂查询 | SqlSessionFactory | 更灵活的配置 |
| 事务管理 | PlatformTransactionManager | 支持声明式和编程式事务 |
| MyBatis Plus | BaseMapper | 自动 CRUD,代码更简洁 |

### 7.4 注入方式选择建议

根据不同场景选择合适的注入方式:

| 场景 | 推荐方式 | 优先级 | 说明 |
|------|---------|--------|------|
| 一个类一个源 | @DataSourceOn | ⭐⭐⭐⭐⭐ | 代码最简洁,语义最清晰 |
| 只用 default | 不用注解 | ⭐⭐⭐⭐ | 依赖 @Primary,简单直接 |
| 一个类多个源 | @DataSourceOn + @Qualifier | ⭐⭐⭐⭐ | 主源用 @DataSourceOn,其他用 @Qualifier |
| 精确控制注入 | @Qualifier | ⭐⭐⭐⭐ | 明确指定,最灵活 |
| 字段名匹配 | @Resource | ⭐⭐⭐⭐ | 优先按名称注入,可靠绕过 @Primary |
| 动态切换源 | MultiDataSourceManager | ⭐⭐⭐⭐⭐ | 运行时动态选择 |
| 多租户场景 | MultiDataSourceManager | ⭐⭐⭐⭐⭐ | 根据租户 ID 动态获取 |
| 代码简洁优先 | @DataSourceOn | ⭐⭐⭐⭐⭐ | 减少样板代码 |

**推荐**: 一个类用一个源选 `@DataSourceOn`, 多个源选 `@Qualifier`, 动态切换选 `MultiDataSourceManager`

### 7.5 安全建议

- 生产环境【强制】配置数据库密码
- 不同业务使用不同的数据库账号
- 敏感配置使用环境变量或配置中心
- 定期检查连接池状态,避免连接泄漏
- 开启 Druid SQL 防火墙,防止 SQL 注入
- 使用只读账号连接从库

### 7.6 性能优化建议

- 合理设置连接池参数,避免频繁创建连接
- 使用批量操作提高性能
- 开启 Druid 监控,及时发现慢 SQL
- 合理使用事务,避免大事务
- 读写分离,降低主库压力
- 使用连接池监控,及时发现问题

### 7.7 MyBatis Plus 最佳实践

- 使用 BaseMapper 简化 CRUD 操作
- 合理使用分页插件,避免全表扫描
- 使用逻辑删除而非物理删除
- 使用乐观锁处理并发更新
- 合理使用缓存,提高查询性能
- 使用 @MapperScan 指定不同数据源的 Mapper

## 8. 监控与运维

### 8.1 监控指标

暴露以下 Actuator 监控指标:

- `ldx2t.datasource.count`:数据源总数
- `ldx2t.datasource.connection.active`:活跃连接数
- `ldx2t.datasource.connection.idle`:空闲连接数
- `ldx2t.datasource.connection.create`:创建连接次数
- `ldx2t.datasource.connection.destroy`:销毁连接次数
- `ldx2t.datasource.health.status`:健康检查状态

### 8.2 Druid 监控

Druid 提供内置监控页面:

```yaml
spring:
  datasource:
    druid:
      stat-view-servlet:
        enabled: true
        url-pattern: /druid/*
        reset-enable: false
        login-username: admin
        login-password: admin
      web-stat-filter:
        enabled: true
        url-pattern: /*
        exclusions: "*.js,*.gif,*.jpg,*.png,*.css,*.ico,/druid/*"
```

访问 `http://localhost:8080/druid/` 查看监控信息。

### 8.3 日志记录

组件自动记录以下日志:

- 数据源初始化和销毁
- 连接池状态变化
- 健康检查结果
- 慢 SQL 记录
- 异常和错误信息

### 8.4 故障排查

#### 问题一:连接超时

**原因**:
- 数据库服务器不可达
- 网络问题
- 连接池耗尽
- 防火墙阻止连接

**解决方案**:
1. 检查数据库服务器状态
2. 检查网络连通性
3. 增大连接池参数
4. 检查是否存在连接泄漏
5. 开启 Druid 连接泄漏检测

#### 问题二:注入失败

**原因**:
- 配置错误
- Bean 名称冲突
- 数据源未启用
- Mapper 扫描路径错误

**解决方案**:
1. 检查 application.yml 配置
2. 确认 `ldx2t.commons.datasource.enabled=true`
3. 检查数据源名称是否唯一
4. 查看启动日志确认 Bean 创建情况
5. 检查 @MapperScan 配置是否正确

#### 问题三:事务不生效

**原因**:
- @Transactional 注解位置错误
- 事务管理器指定错误
- 方法不是 public
- 类内部调用

**解决方案**:
1. 确保 @Transactional 在 public 方法上
2. 多数据源场景明确指定事务管理器
3. 避免类内部方法调用
4. 检查事务传播行为配置

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
