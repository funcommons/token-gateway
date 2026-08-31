---
name: fwk4j-sql-tracing
description: framework4j SQL 追踪（Druid Filter 注入 trace_id 到 SQL 注释 + 3 模式 DISABLED/WRITE_ONLY/ALL + MDC 自动取）。触发词：SQL 追踪、trace_id、SQL 注释、Druid Filter、TracingMode、sql-tracing、慢 SQL 定位。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-data
  tags: [sql-tracing, druid, trace-id]
  language: zh-CN
  artifactId: framework4j-sql-tracing
  config-prefix: framework4j.datasource.sql-tracing
  examples:
    - "SQL 怎么关联 trace_id"         # → Druid Filter 自动注入
    - "慢 SQL 怎么定位是哪个请求"      # → SQL 注释中的 trace_id
    - "只追踪写 SQL"                  # → mode: WRITE_ONLY
---

# framework4j-sql-tracing SQL 追踪

## 效果

```sql
/*traceid=abc123,topic=my-app*/ SELECT * FROM orders WHERE id = 1
```

在日志 / Druid 监控页 / 慢 SQL 报告中可直接关联到发起请求。

## 3 种模式

| 模式 | 行为 |
|---|---|
| `DISABLED` | 不注入 |
| `WRITE_ONLY` | 仅 INSERT/UPDATE/DELETE |
| `ALL`（默认） | 所有 SQL |

## 配置

```yaml
framework4j:
  datasource:
    sql-tracing:
      enabled: true
      mode: ALL
      topic: my-app
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-sql-tracing</artifactId>
    <version>v1.1.1</version>
</dependency>
```
