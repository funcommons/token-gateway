---
name: fwk4j-datetime
description: framework4j 时间处理（OffsetDateTime 序列化 + @LocalTimeFormat 拦截器 + 多格式输入转换 + Long→String）。触发词：OffsetDateTime、时间序列化、LocalTimeFormat、时间格式、时间转换、ISO-8601、时区、时间拦截器。
version: 1.0.0
enabled: true
metadata:
  type: module-spec
  category: backend-data
  tags: [datetime, jackson, offsetdatetime]
  language: zh-CN
  artifactId: framework4j-datetime
  config-prefix: framework4j.datetime
  examples:
    - "时间序列化格式"                # → ISO-8601 + Long→String
    - "前端传时间参数怎么接收"         # → @LocalTimeFormat
    - "多格式时间输入"               # → 自动识别
---

# framework4j-datetime 时间处理

## OffsetDateTime 序列化（全局自动）

Jackson 自动配置：
- `OffsetDateTime` → ISO-8601 字符串
- `Long` → `String`（防 JS 精度丢失）

## @LocalTimeFormat 注解

```java
@GetMapping("/report")
public ApiResponse<Report> getReport(
    @LocalTimeFormat @RequestParam OffsetDateTime start) { ... }
```

支持多种输入：
- `2024-12-10T14:30:45+08:00`
- `2024-12-10 14:30:45`
- 时间戳（毫秒）

## 配置

```yaml
framework4j:
  datetime:
    enabled: true
```

## 引入

```xml
<dependency>
    <groupId>com.github.funcommons.framework4j</groupId>
    <artifactId>framework4j-datetime</artifactId>
    <version>v1.1.1</version>
</dependency>
```
