# framework4j-web

> Web 层契约落地：`ApiResponse` 信封 + `GlobalExceptionHandler` + `TraceContext` + `CachedBodyRequestWrapper`（多模块共用资产）

## 简介

`framework4j-web` 是 `framework4j` 的 Web 层契约模块，从 `framework4j-api` 拆分而来：

- **`framework4j-api`** 仅保留 `ApiCode` 错误码枚举（契约定义）
- **`framework4j-web`** 承载所有 Web 层落地实现

## 核心能力

| 类 | 职责 |
|---|---|
| `ApiResponse<T>` | 6 字段信封（code/message/data/error/trace_id/timestamp） |
| `ApiException` | 业务异常基类 |
| `ApiAssert` | 链式断言工具 |
| `TraceContext` | trace_id 读写（MDC + Micrometer Tracer 兜底） |
| `GlobalExceptionHandler` | `@RestControllerAdvice` 全局异常处理（业务异常 HTTP 200 / 系统异常 HTTP 500） |
| `CachedBodyRequestWrapper` | **解决 Spring `ContentCachingRequestWrapper` 不重放 InputStream 的隐藏 bug**（共用资产，供 signature / idempotency 模块使用） |

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-web</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 启用配置

```yaml
framework4j:
  web:
    enabled: true  # 默认开启
```

### 3. 使用 ApiResponse

```java
@RestController
public class OrderController {

    @GetMapping("/v1/orders/{id}")
    public ApiResponse<Order> getOrder(@PathVariable String id) {
        Order order = orderService.get(id);
        return ApiResponse.success(order);
    }

    @PostMapping("/v1/orders")
    public ApiResponse<Order> createOrder(@RequestBody CreateOrderRequest req) {
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return ApiResponse.fail(ApiCode.PARAM_MISSING, "items 不能为空");
        }
        return ApiResponse.success(orderService.create(req));
    }
}
```

### 4. 异常处理

业务代码抛 `ApiException`，`GlobalExceptionHandler` 自动转为信封：

```java
public Order getOrder(String id) {
    Order order = repo.findById(id);
    if (order == null) {
        throw new ApiException(ApiCode.NOT_FOUND, "订单不存在: " + id);
    }
    return order;
}
```

响应（HTTP 200 + 信封 code）：

```json
{
  "code": 10400,
  "message": "订单不存在: abc",
  "data": null,
  "error": null,
  "trace_id": "c0a8010116983728001",
  "timestamp": 1718660400000
}
```

## 模块依赖

```
framework4j-api  ── ApiCode（契约）
       ↓
framework4j-web  ── Web 层实现
       ↑
  signature / rate-limit / idempotency / accesstoken
```

## 自动装配

通过 `META-INF/spring/...AutoConfiguration.imports` 注册：

- `WebAutoConfiguration`（注册 `GlobalExceptionHandler` + `TraceConfig` + `WebConfig`）
- 通过 `framework4j.web.enabled=false` 关闭（默认开启）

## 信封规范（对齐 mc-api-spec v1.6 §4）

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | 0 = 成功；非 0 = 错误码（5 位分段） |
| `message` | string | 用户可读消息 |
| `data` | T | 业务数据；**失败时必须 null**（10700 部分成功例外） |
| `error` | List<ApiError> | 字段级错误明细（参数校验场景） |
| `trace_id` | string | 链路追踪 ID（双通道：body + `X-Trace-Id` Header） |
| `timestamp` | long | 响应时间戳（毫秒） |

## 相关文档

- `Java开发准则.md` §15 Web 层契约
- `mc-api-spec` v1.6 §4 响应信封规范
