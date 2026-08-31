← [返回 README](./README.md)

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

