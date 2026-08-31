← [返回 README](./README.md)

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

