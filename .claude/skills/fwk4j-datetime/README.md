# framework4j-datetime

> `OffsetDateTime` / `LocalDateTime` 序列化、时间格式化拦截器、北京时间默认时区。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | 全局时间序列化（ISO-8601 / 北京时间）、`TimeFormatInterceptor` 路径级格式化、`OffsetDateTime` ↔ `Long` 转换器 |
| 配置前缀 | `framework4j.datetime.enabled`（默认 `true`） |
| 必需依赖 | `spring-boot-starter`、`spring-boot-starter-web`（optional） |
| 可选依赖 | `framework4j-api`（仅 test scope） |
| 在 SDK 中的位置 | 基础层，独立于 `id` / `redis` / `datasource`，可单独引入 |

**核心原则**：服务端统一存 `OffsetDateTime`（UTC），API 响应按需输出 ISO-8601 字符串或时间戳（毫秒），前端不参与时区转换。

## 2. 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-datetime</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最小 application.yml

```yaml
framework4j:
  datetime:
    enabled: true  # 默认 true
```

### 最小代码示例

```java
public record OrderVO(
    String id,
    OffsetDateTime createdAt,   // 自动序列化为 "2026-07-04T15:30:00+08:00"
    LocalDateTime paidAt        // 自动序列化为 "2026-07-04T15:30:00"
) {}
```

无需任何注解，`DateTimeAutoConfiguration` 自动注册 Jackson 序列化器。

## 3. 配置参考

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.datetime.enabled` | `boolean` | `true` | 是否启用本模块 |
| `framework4j.datetime.default-zone` | `String` | `Asia/Shanghai` | 默认时区 |
| `framework4j.datetime.use-iso-format` | `boolean` | `true` | `true` 输出 ISO-8601，`false` 输出时间戳（毫秒） |

## 4. API 参考

### `DateTimeAutoConfiguration`

注册以下 Jackson 组件到 `ObjectMapper`：
- `OffsetDateTimeSerializer` → ISO-8601 字符串
- `LocalDateTimeSerializer` → `yyyy-MM-dd HH:mm:ss`
- `JavaTimeModule`（JSR-310）
- `OffsetDateTime` ↔ `Long` 转换器（用于 MyBatis）

### `TimeFormatInterceptor`

`HandlerInterceptor`，按请求路径匹配时间格式化策略：

```java
@Configuration
public class MyTimeFormatConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TimeFormatInterceptor())
                .addPathPatterns("/v1/admin/**")
                .excludePathPatterns("/v1/admin/reports/**");
    }
}
```

### `@TimeFormat`（注解）

```java
public record ReportVO(
    @TimeFormat(pattern = "yyyy年MM月dd日") LocalDate reportDate,
    @TimeFormat(pattern = "HH:mm:ss") LocalTime openTime
) {}
```

## 5. 示例

### 5.1 ISO-8601 默认输出

```java
@GetMapping("/v1/orders/{id}")
public ApiResponse<OrderVO> getOrder(@PathVariable String id) {
    OrderVO order = orderService.find(id);
    // createdAt: OffsetDateTime.now() → "2026-07-04T15:30:00+08:00"
    return ApiResponse.success(order, TraceContext.getTraceId());
}
```

### 5.2 时间戳输出（前端图表）

```yaml
framework4j:
  datetime:
    use-iso-format: false  # 输出 1718660400000
```

### 5.3 自定义格式

```java
public record UserVO(
    String id,
    @TimeFormat(pattern = "yyyy-MM-dd") LocalDate birthday,
    @TimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime lastLoginAt
) {}
```

## 6. 错误码

本模块不定义自己的错误码。时间格式解析失败时由 `GlobalExceptionHandler` 兜底返回 `10102 FORMAT_INVALID`。

## 7. FAQ

**Q1：为什么默认用 `OffsetDateTime` 而不是 `LocalDateTime`？**
A：`OffsetDateTime` 带时区信息，跨时区传输无歧义。`LocalDateTime` 不带时区，存数据库时依赖服务器时区设置，容易出 bug。

**Q2：前端需要时间戳怎么办？**
A：`framework4j.datetime.use-iso-format=false`，所有时间字段输出毫秒时间戳（`Long` 类型，序列化为 `String` 防精度丢失）。

**Q3：`TimeFormatInterceptor` 和 `@TimeFormat` 区别？**
A：`Interceptor` 按路径批量配置（适合后台管理统一格式）；`@TimeFormat` 按字段精细控制（适合个别特殊字段）。

**Q4：数据库存 `DATETIME` 还是 `TIMESTAMP`？**
A：推荐 `DATETIME` + MyBatis `OffsetDateTimeTypeHandler`。`TIMESTAMP` 受 MySQL `time_zone` 影响，跨地域部署易错。

**Q5：如何禁用本模块？**
A：`framework4j.datetime.enabled=false`。需要自行注册 `JavaTimeModule`，否则 Jackson 默认序列化 `OffsetDateTime` 会抛 `InvalidDefinitionException`。
