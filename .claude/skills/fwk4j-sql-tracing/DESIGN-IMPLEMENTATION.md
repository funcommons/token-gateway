← [返回 README](./README.md)

## 3. 详细代码实现

### 3.1 依赖管理

本方案已集成在 `ldx2t-commons-datasource` 模块中,使用时只需引入:

```xml
<dependency>
    <groupId>com.ldx2t</groupId>
    <artifactId>ldx2t-commons-datasource</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**内部依赖包括:**

```xml
<!-- Druid 数据源 -->
<dependency>
    <groupId>com.alibaba</groupId>
    <artifactId>druid-spring-boot-starter</artifactId>
</dependency>

<!-- SLF4J MDC 支持 (Spring Boot 自带) -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
</dependency>

<!-- Micrometer Tracing (可选,用于分布式追踪) -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
    <optional>true</optional>
</dependency>
```

---

### 3.2 核心过滤器实现 (TraceIdDruidFilter)

**文件位置:** `com.ldx2t.commons.datasource.tracing.TraceIdDruidFilter`

**核心特性:**

1. ✅ 支持三种追踪模式: `DISABLED`, `WRITE_ONLY`, `ALL`
2. ✅ 拦截所有 JDBC 执行方法 (PreparedStatement, Statement)
3. ✅ 防重复注入检测
4. ✅ 读写操作智能识别
5. ✅ 异常兜底保护
6. ✅ 特殊字符过滤 (防注入)

**关键代码片段:**

```java
package com.ldx2t.commons.datasource.tracing;

public class TraceIdDruidFilter extends FilterEventAdapter {

    private final TraceIdProvider traceIdProvider;
    private final String topic;
    private final SqlTracingProperties.TracingMode tracingMode;

    // ==================== PreparedStatement 拦截 ====================

    @Override
    public PreparedStatementProxy connection_prepareStatement(
            FilterChain chain, ConnectionProxy connection, String sql) throws SQLException {
        String finalSql = processSql(sql);
        return super.connection_prepareStatement(chain, connection, finalSql);
    }

    // ==================== Statement executeQuery 拦截 ====================

    @Override
    public ResultSetProxy statement_executeQuery(
            FilterChain chain, StatementProxy statement, String sql) throws SQLException {
        String finalSql = processSql(sql);
        return super.statement_executeQuery(chain, statement, finalSql);
    }

    // ==================== SQL 处理核心逻辑 ====================

    private String processSql(String sql) {
        // 1. 追踪模式检查
        if (tracingMode == SqlTracingProperties.TracingMode.DISABLED) {
            return sql;
        }

        // 2. 基础校验
        if (sql == null || sql.isBlank()) {
            return sql;
        }

        try {
            String trimmedSql = sql.trim();

            // 3. 防止重复添加
            if (trimmedSql.startsWith("/*traceid=")) {
                return sql;
            }

            // 4. WRITE_ONLY 模式下，跳过读操作
            if (tracingMode == SqlTracingProperties.TracingMode.WRITE_ONLY
                    && isReadOperation(trimmedSql)) {
                return sql;
            }

            // 5. 获取当前 TraceID
            String traceId = getTraceId();

            // 6. 拼接 SQL 注释
            // 格式: /*traceid=xxx,topic=xxx*/ SQL
            StringBuilder sb = new StringBuilder(sql.length() + 64);
            sb.append("/*traceid=").append(sanitize(traceId))
              .append(",topic=").append(sanitize(topic))
              .append("*/ ")
              .append(sql);

            return sb.toString();

        } catch (Exception e) {
            // 异常兜底：确保不会因为追踪逻辑报错而影响业务 SQL 执行
            log.warn("Failed to inject TraceID into SQL: {}", e.getMessage());
            return sql;
        }
    }

    /**
     * 判断是否为读操作
     * 读操作包括: SELECT, SHOW, DESCRIBE, EXPLAIN 等
     */
    private boolean isReadOperation(String sql) {
        String upperSql = sql.toUpperCase(Locale.ROOT);
        return upperSql.startsWith("SELECT ")
                || upperSql.startsWith("SELECT\t")
                || upperSql.startsWith("SELECT\n")
                || upperSql.startsWith("(SELECT ")
                || upperSql.startsWith("SHOW ")
                || upperSql.startsWith("DESCRIBE ")
                || upperSql.startsWith("DESC ")
                || upperSql.startsWith("EXPLAIN ");
    }

    /**
     * 获取当前 TraceID
     */
    private String getTraceId() {
        if (traceIdProvider == null) {
            return "none";
        }

        String traceId = traceIdProvider.getTraceId();
        if (traceId == null || traceId.isBlank()) {
            return "none";
        }
        return traceId;
    }

    /**
     * 过滤特殊字符，防止注入攻击和注释结构破坏
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        // 移除 *, /, 换行符等可能破坏注释结构的字符
        return value.replaceAll("[*/\\r\\n]", "");
    }
}
```

**拦截的 JDBC 方法清单:**

| 方法类型                      | 拦截方法                                        | 说明                        |
|-------------------------------|------------------------------------------------|----------------------------|
| PreparedStatement 创建        | `connection_prepareStatement` (6 个重载)       | MyBatis, JPA 主要走这个路径  |
| Statement execute             | `statement_execute` (4 个重载)                 | 直接执行 SQL                |
| Statement executeUpdate       | `statement_executeUpdate` (4 个重载)           | 写操作执行                  |
| Statement executeQuery        | `statement_executeQuery`                       | 查询操作执行                |

---

### 3.3 TraceID 提供者实现 (DefaultTraceIdProvider)

**文件位置:** `com.ldx2t.commons.datasource.tracing.DefaultTraceIdProvider`

**核心逻辑:** 从 SLF4J MDC 中按优先级获取 TraceID

```java
package com.ldx2t.commons.datasource.tracing;

import org.slf4j.MDC;

public class DefaultTraceIdProvider implements TraceIdProvider {

    /**
     * 常见的 TraceId MDC 键名 (按优先级排序)
     */
    private static final String[] TRACE_ID_KEYS = {
            "traceId",           // 1. 通用命名
            "trace_id",          // 2. 下划线风格
            "X-B3-TraceId",      // 3. Zipkin / SkyWalking
            "X-Request-Id",      // 4. HTTP 请求 ID
            "requestId",         // 5. 驼峰命名
            "request_id"         // 6. 下划线风格
    };

    @Override
    public String getTraceId() {
        // 优先尝试常见的 traceId 键名
        for (String key : TRACE_ID_KEYS) {
            String traceId = MDC.get(key);
            if (traceId != null && !traceId.isBlank()) {
                return traceId;
            }
        }
        return null; // 未找到则返回 null (Filter 会转为 "none")
    }
}
```

**MDC 键名优先级表:**

| 优先级 | MDC 键名          | 来源/场景                              |
|--------|-------------------|---------------------------------------|
| 1      | `traceId`         | 手动设置 / Spring Cloud Sleuth        |
| 2      | `trace_id`        | 下划线命名风格                        |
| 3      | `X-B3-TraceId`    | Zipkin / SkyWalking Agent             |
| 4      | `X-Request-Id`    | 标准 HTTP 请求 ID                     |
| 5      | `requestId`       | 驼峰命名风格                          |
| 6      | `request_id`      | 下划线命名风格                        |

---

### 3.4 自动配置类 (SqlTracingAutoConfiguration)

**文件位置:** `com.ldx2t.commons.datasource.tracing.SqlTracingAutoConfiguration`

**核心职责:**

1. ✅ 实现 `BeanPostProcessor` 拦截 `DruidDataSource` 初始化
2. ✅ 实现 `EnvironmentAware` 读取配置
3. ✅ 自动检测 Micrometer Tracing
4. ✅ 为每个数据源注册独立的 `TraceIdDruidFilter`

**关键代码片段:**

```java
package com.ldx2t.commons.datasource.tracing;

@Slf4j
public class SqlTracingAutoConfiguration implements BeanPostProcessor, EnvironmentAware {

    private Environment environment;
    private String applicationName;
    private TraceIdProvider traceIdProvider;

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        this.environment = environment;
        this.applicationName = environment.getProperty("spring.application.name", "unknown");
        this.traceIdProvider = createTraceIdProvider();
    }

    /**
     * 创建 TraceIdProvider (自动检测 Micrometer)
     */
    private TraceIdProvider createTraceIdProvider() {
        try {
            Class.forName("io.micrometer.tracing.Tracer");
            log.debug("Micrometer Tracing detected, using DefaultTraceIdProvider with MDC fallback");
        } catch (ClassNotFoundException e) {
            log.debug("Micrometer Tracing not found, using DefaultTraceIdProvider");
        }
        return new DefaultTraceIdProvider();
    }

    @Override
    public Object postProcessBeforeInitialization(@NonNull Object bean, @NonNull String beanName) {
        if (bean instanceof DruidDataSource druidDataSource) {
            // 从 Bean 名称中提取数据源名称 (格式: {name}DataSource)
            String datasourceName = extractDatasourceName(beanName);
            SqlTracingProperties tracingProps = getTracingProperties(datasourceName);

            if (tracingProps != null && tracingProps.isEnabled()) {
                addTraceFilter(druidDataSource, datasourceName, tracingProps);
            }
        }
        return bean;
    }

    /**
     * 获取数据源的追踪配置
     */
    private SqlTracingProperties getTracingProperties(String datasourceName) {
        try {
            String prefix = "ldx2t.commons.datasource.datasources." + datasourceName + ".sql-tracing";
            return Binder.get(environment)
                    .bind(prefix, SqlTracingProperties.class)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Failed to bind tracing properties for {}: {}", datasourceName, e.getMessage());
            return null;
        }
    }

    /**
     * 为 DruidDataSource 添加追踪 Filter
     */
    private void addTraceFilter(DruidDataSource dataSource, String datasourceName, SqlTracingProperties props) {
        // 确定 topic (优先使用配置,否则使用 spring.application.name)
        String topic = StringUtils.hasText(props.getTopic()) ? props.getTopic() : applicationName;

        // 创建 Filter
        TraceIdDruidFilter filter = new TraceIdDruidFilter(traceIdProvider, topic, props.getMode());

        // 添加到 DataSource
        List<com.alibaba.druid.filter.Filter> filters = dataSource.getProxyFilters();
        if (filters == null) {
            filters = new ArrayList<>();
        } else {
            filters = new ArrayList<>(filters);
        }
        filters.add(filter);
        dataSource.setProxyFilters(filters);

        log.info("SQL Tracing enabled for datasource [{}], mode: {}, topic: {}",
                datasourceName, props.getMode(), topic);
    }
}
```

---

### 3.5 配置属性类 (SqlTracingProperties)

**文件位置:** `com.ldx2t.commons.datasource.tracing.SqlTracingProperties`

```java
package com.ldx2t.commons.datasource.tracing;

@Data
public class SqlTracingProperties {

    /**
     * 追踪模式
     */
    private TracingMode mode = TracingMode.ALL;

    /**
     * 应用名称 (topic)
     * 如果不配置，将自动从 spring.application.name 获取
     */
    private String topic;

    /**
     * SQL 追踪模式枚举
     */
    public enum TracingMode {
        /** 不开启追踪 */
        DISABLED,

        /** 仅追踪写操作 (INSERT, UPDATE, DELETE) */
        WRITE_ONLY,

        /** 追踪所有 SQL (SELECT, INSERT, UPDATE, DELETE 等) */
        ALL
    }

    public boolean isEnabled() {
        return mode != null && mode != TracingMode.DISABLED;
    }
}
```

---

## 4. 配置示例

### 4.1 单数据源配置

**application.yml:**

```yaml
spring:
  application:
    name: my-application

ldx2t:
  commons:
    datasource:
      datasources:
        default:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://localhost:5432/mydb
          username: postgres
          password: password
          sql-tracing:
            mode: ALL  # 追踪所有 SQL
            topic: my-application  # 可选,默认使用 spring.application.name
```

**效果:**

```sql
-- 原始 SQL
SELECT id, name FROM users WHERE id = ?

-- 实际发送给数据库的 SQL
/*traceid=abc123xyz,topic=my-application*/ SELECT id, name FROM users WHERE id = ?
```

---

### 4.2 多数据源配置

```yaml
spring:
  application:
    name: multi-datasource-app

ldx2t:
  commons:
    datasource:
      datasources:
        # 主数据源 - 追踪所有 SQL
        primary:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://localhost:5432/primary
          username: postgres
          password: password
          sql-tracing:
            mode: ALL
            topic: primary-db

        # 从数据源 - 仅追踪写操作
        secondary:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://localhost:5432/secondary
          username: postgres
          password: password
          sql-tracing:
            mode: WRITE_ONLY
            topic: secondary-db

        # 缓存数据源 - 禁用追踪
        cache:
          driver-class-name: org.postgresql.Driver
          url: jdbc:postgresql://localhost:5432/cache
          username: postgres
          password: password
          sql-tracing:
            mode: DISABLED
```

---

### 4.3 追踪模式对比

| 模式          | SELECT | INSERT | UPDATE | DELETE | DDL   | 适用场景                          |
|--------------|--------|--------|--------|--------|-------|----------------------------------|
| `ALL`        | ✅     | ✅     | ✅     | ✅     | ✅    | 生产环境全量追踪                  |
| `WRITE_ONLY` | ❌     | ✅     | ✅     | ✅     | ✅    | 仅追踪数据变更                    |
| `DISABLED`   | ❌     | ❌     | ❌     | ❌     | ❌    | 开发环境或高性能场景              |

---

