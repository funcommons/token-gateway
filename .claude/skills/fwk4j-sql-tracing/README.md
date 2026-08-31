# framework4j-sql-tracing

> Druid Filter 注入 `trace_id` 到 SQL 注释，全链路 SQL 可追溯。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | `TraceIdDruidFilter`（拦截 SQL，注入 `/*traceid=xxx,topic=xxx*/` 前缀）/ `DefaultTraceIdProvider`（从 MDC 取 trace_id）/ 3 种 `TracingMode`（DISABLED / WRITE_ONLY / ALL） |
| 配置前缀 | `framework4j.datasource.sql-tracing.*`、`framework4j.datasource.datasources.<name>.sql-tracing.*` |
| 必需依赖 | `spring-boot-starter`、`druid-spring-boot-starter` |
| 可选依赖 | `micrometer-tracing`（自动从 MDC 取 trace_id） |
| 在 SDK 中的位置 | 数据访问层，配合 `framework4j-datasource` 使用，可单独引入 |

**核心原则**：每条 SQL 自动带 `trace_id`，慢查询 / 异常 SQL 可定位到具体请求。TraceId 来自 MDC（Micrometer Tracing 自动写入）。

## 2. 快速开始

### Maven 依赖

```xml
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
  datasource:
    type: com.alibaba.druid.pool.DruidDataSource
    url: jdbc:mysql://localhost:3306/mydb
    username: root
    password: ${DB_PASSWORD}

framework4j:
  datasource:
    sql-tracing:
      enabled: true  # 默认 true
    datasources:
      default:       # 默认数据源
        sql-tracing:
          mode: ALL  # ALL / WRITE_ONLY / DISABLED
```

### 最小代码示例

无需任何代码。Filter 自动注入到 `DruidDataSource`，所有 SQL 自动带注释：

```sql
-- 原始 SQL
SELECT * FROM users WHERE id = 1

-- 实际执行
/*traceid=abc-123-trace,topic=my-app*/ SELECT * FROM users WHERE id = 1
```

## 3. 配置参考

### 全局开关

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.datasource.sql-tracing.enabled` | `boolean` | `true` | 是否启用本模块（matchIfMissing） |

### 每数据源配置

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.datasource.datasources.<name>.sql-tracing.mode` | `TracingMode` | `ALL` | `DISABLED` / `WRITE_ONLY` / `ALL` |
| `framework4j.datasource.datasources.<name>.sql-tracing.topic` | `String` | `spring.application.name` | SQL 注释中的 `topic` 字段 |
| `framework4j.datasource.datasources.<name>.sql-tracing.provider` | `String` | `auto` | `auto` / `micrometer` / `mdc` / `none`（暂未实现切换，预留） |

### `TracingMode` 枚举

| 值 | 说明 |
|---|---|
| `DISABLED` | 不开启追踪 |
| `WRITE_ONLY` | 仅追踪写操作（INSERT / UPDATE / DELETE），跳过 SELECT / SHOW / DESCRIBE / EXPLAIN |
| `ALL` | 追踪所有 SQL（默认） |

## 4. API 参考

### `TraceIdDruidFilter`

继承 `FilterEventAdapter`，拦截以下方法：
- `connection_prepareStatement`（5 个重载）
- `statement_execute`（4 个重载）
- `statement_executeUpdate`（4 个重载）
- `statement_executeQuery`

每个方法在调用 `super` 前对 SQL 调用 `processSql(sql)`，注入 `/*traceid=xxx,topic=xxx*/` 前缀。

**关键防御**：
- 已有 `/*traceid=` 前缀的 SQL 不重复注入（防重试重复）
- `sanitize()` 移除 `*` / `/` / `\r` / `\n` 字符（防注入）
- `tracingMode=null` 时按 `DISABLED` 处理（防御）
- 异常时返回原始 SQL（不影响业务）

### `TraceIdProvider`（接口）

```java
@FunctionalInterface
public interface TraceIdProvider {
    String getTraceId();
}
```

### `DefaultTraceIdProvider`

从 SLF4J MDC 按 6 个键名优先级查找：

1. `traceId`
2. `trace_id`
3. `X-B3-TraceId`
4. `X-Request-Id`
5. `requestId`
6. `request_id`

空白值跳过，继续找下一个。全空返回 `null`，Filter 内部用 `"none"` 占位。

**自定义 Provider**：

```java
@Bean
public TraceIdProvider traceIdProvider() {
    return () -> MyCustomContext.getTraceId();
}
```

`SqlTracingAutoConfiguration` 会优先用容器中的 `TraceIdProvider` Bean。

### `SqlTracingAutoConfiguration`

`BeanPostProcessor`，在 `DruidDataSource` 初始化前读取每数据源的 `sql-tracing.*` 配置，调用 `addTraceFilter()` 注入 Filter。Bean 名 `xxxDataSource` 自动解析为数据源名 `xxx`。

## 5. 示例

### 5.1 多数据源差异化配置

```yaml
framework4j:
  datasource:
    datasources:
      order:           # 写多，追踪 ALL
        sql-tracing:
          mode: ALL
          topic: order-service
      cache:           # 缓存库，只追踪写
        sql-tracing:
          mode: WRITE_ONLY
      log:             # 日志库，不追踪
        sql-tracing:
          mode: DISABLED
```

### 5.2 配合 Micrometer Tracing

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

`TraceContext.getTraceId()` 自动写入 MDC，Filter 自动读取。

### 5.3 自定义 TraceId 来源（如网关透传）

```java
@Component
public class HeaderTraceIdProvider implements TraceIdProvider {
    @Override
    public String getTraceId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getHeader("X-Trace-Id");
        }
        return null;
    }
}
```

## 6. 错误码

本模块不定义自己的错误码。`TraceIdDruidFilter` 内部异常会 `log.warn` 并返回原始 SQL，不影响业务执行。

## 7. FAQ

**Q1：SQL 注释会影响 SQL 执行吗？**
A：不会。`/*traceid=xxx,topic=xxx*/` 是标准 SQL 注释，MySQL / PostgreSQL / Oracle / SQL Server 都支持。慢查询日志、执行计划、explain 都能正常工作。

**Q2：注释会让 SQL 变长，影响性能吗？**
A：注释固定长度约 60 字符（含 trace_id）。对网络传输和 SQL 解析影响可忽略（< 0.1ms）。

**Q3：如何查看带 trace_id 的 SQL？**
A：Druid 监控页（`/druid/sql.html`）、MySQL 慢查询日志、`SHOW PROCESSLIST` 都能看到。grep `traceid=abc-123` 可定位具体请求。

**Q4：`WRITE_ONLY` 模式下，子查询 `(SELECT ...)` 会跳过吗？**
A：会。Filter 用 `startsWith` 检测 `(SELECT` / `( SELECT` / `SHOW` / `DESCRIBE` / `DESC` / `EXPLAIN`，所有读操作（含子查询）都跳过。

**Q5：怎么禁用某个数据源的追踪？**
A：`framework4j.datasource.datasources.<name>.sql-tracing.mode=DISABLED`。或全局禁用：`framework4j.datasource.sql-tracing.enabled=false`。

## 相关文档

- [SQL 追踪设计方案](./DESIGN.md) — Spring Boot 3 + Micrometer 全链路 SQL 追踪方案
- [疑难解答](./TROUBLESHOOTING.md) — TraceID 常见问题与排障指南

## 📚 文档导航

| 我想… | 看这个文档 |
|---|---|
| 了解架构设计 | [架构设计](./DESIGN-ARCHITECTURE.md) |
| 看代码实现 | [实现详解](./DESIGN-IMPLEMENTATION.md) |
| 运维与性能 | [运维指南](./DESIGN-OPERATIONS.md) |
| TraceID 没出现 | [诊断指南](./TROUBLESHOOTING-DIAGNOSTIC.md) |
| FAQ | [常见问题](./TROUBLESHOOTING-FAQ.md) |
