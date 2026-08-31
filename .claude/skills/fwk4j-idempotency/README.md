# framework4j-idempotency

> `Idempotency-Key` 拦截器：Redis 48h 保留，防重放 / 防重复提交。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | `IdempotencyInterceptor`（拦截写操作）/ `IdempotencyProperties`（配置）/ SHA-256 请求体哈希 / Redis 原子 SETNX 防重放 |
| 配置前缀 | `framework4j.idempotency.*` |
| 必需依赖 | `framework4j-redis`、`framework4j-api`、`spring-boot-starter-web`、`jackson-databind` |
| 可选依赖 | — |
| 在 SDK 中的位置 | 安全层，独立于 `accesstoken`，可单独引入 |

**核心原则**（mc-api-spec §5 铁律 8）：所有写操作支持 `Idempotency-Key` Header（客户端 UUID v4）；服务端 48h 内同 key + 同 Body 返回首次结果；同 key + 不同 Body 返 `10501 DUPLICATE_SUBMIT`。

## 2. 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-idempotency</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最小 application.yml

```yaml
spring:
  application:
    name: my-app

framework4j:
  idempotency:
    enabled: true
    redis-name: default
    ttl-seconds: 172800  # 48h
    header-name: Idempotency-Key
    paths:
      - /v1/orders
      - /v1/payments/**
```

### 最小代码示例

```java
// 消费者应用：发请求时带 Idempotency-Key
@PostMapping("/v1/orders")
public ApiResponse<OrderVO> createOrder(
        @RequestHeader("Idempotency-Key") String key,
        @RequestBody CreateOrderRequest req) {
    // 拦截器已自动校验：同 key + 同 Body 返回首次结果
    return ApiResponse.success(orderService.create(req), TraceContext.getTraceId());
}
```

客户端：

```bash
curl -X POST https://api.example.com/v1/orders \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Content-Type: application/json" \
  -d '{"address_id":"123","items":[{"sku_id":"A","quantity":2}]}'
```

第二次相同请求（48h 内）返回首次的完整响应，不会创建第二个订单。

## 3. 配置参考

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.idempotency.enabled` | `boolean` | `false` | 是否启用（opt-in） |
| `framework4j.idempotency.redis-name` | `String` | `default` | 用 `framework4j-redis` 的哪个数据源 |
| `framework4j.idempotency.ttl-seconds` | `long` | `172800`（48h） | Idempotency-Key 保留时长 |
| `framework4j.idempotency.header-name` | `String` | `Idempotency-Key` | Header 名（可自定义） |
| `framework4j.idempotency.paths` | `List<String>` | `[]` | 拦截路径（Ant 风格） |

## 4. API 参考

### `IdempotencyInterceptor`

`HandlerInterceptor`，拦截 `paths` 配置的路径：

1. 读 `Idempotency-Key` Header，无则放行（不强制）
2. 计算 Body 的 SHA-256 哈希
3. Redis `SETNX {app}:idem:{key} {hash}|{marker}` + TTL 48h
   - 设置成功 → 首次请求，放行，响应回写时存入 Redis
   - 已存在 + hash 匹配 → 重放，返回首次响应
   - 已存在 + hash 不匹配 → `10501 DUPLICATE_SUBMIT`
4. 响应回写时把 `ApiResponse` JSON 存入 Redis（同 key）

### `IdempotencyProperties`

```java
@Data
public class IdempotencyProperties {
    private boolean enabled = false;
    private String redisName = "default";
    private long ttlSeconds = 172800;
    private String headerName = "Idempotency-Key";
    private List<String> paths = new ArrayList<>();
}
```

## 5. 示例

### 5.1 订单创建（防重复提交）

```java
@RestController
public class OrderController {
    @PostMapping("/v1/orders")
    @Idempotent  // 自定义注解（可选），或用 paths 配置
    public ApiResponse<OrderVO> createOrder(@RequestBody CreateOrderRequest req) {
        return ApiResponse.success(orderService.create(req), TraceContext.getTraceId());
    }
}
```

```yaml
framework4j:
  idempotency:
    paths:
      - /v1/orders
      - /v1/payments
```

### 5.2 客户端配合

```javascript
// 前端 axios 拦截器
axios.interceptors.request.use(config => {
    if (['post', 'put', 'patch', 'delete'].includes(config.method)) {
        config.headers['Idempotency-Key'] = crypto.randomUUID();
    }
    return config;
});
```

### 5.3 重放场景

```bash
# 第一次：创建订单
curl -X POST /v1/orders -H "Idempotency-Key: abc-123" -d '{"items":[...]}'
# → 200 OK, {"order_id":"OD001",...}

# 第二次：相同 key + 相同 Body（48h 内）
curl -X POST /v1/orders -H "Idempotency-Key: abc-123" -d '{"items":[...]}'
# → 200 OK, {"order_id":"OD001",...}  # 返回首次结果，不创建新订单

# 第三次：相同 key + 不同 Body
curl -X POST /v1/orders -H "Idempotency-Key: abc-123" -d '{"items":[不同]}'
# → 200 OK, {"code":10501,"message":"请勿重复提交"}
```

## 6. 错误码

| Code | 名称 | 触发场景 |
|---|---|---|
| `10501` | `DUPLICATE_SUBMIT` | 同 `Idempotency-Key` + 不同 Body |

## 7. FAQ

**Q1：GET 请求需要 `Idempotency-Key` 吗？**
A：不需要。GET 天然幂等（无副作用）。本模块只拦截写操作（POST / PUT / PATCH / DELETE）。`paths` 配置时只配写端点。

**Q2：Redis 挂了怎么办？**
A：拦截器内部 `try/catch`，Redis 异常时放行（不阻塞业务）。但失去幂等性保证。建议 Redis 配主从 + 哨兵。

**Q3：48h 后再请求会怎样？**
A：Redis key 已过期，按首次请求处理。如果业务需要更长保留期，调大 `ttl-seconds`（但占用 Redis 内存）。

**Q4：Body 哈希用什么算法？**
A：SHA-256（JDK `MessageDigest`）。Body 直接哈希，不解析 JSON。即使字段顺序不同，序列化后的字节流相同就视为同 Body（前端应保证序列化稳定）。

**Q5：响应怎么缓存？**
A：`IdempotencyInterceptor` 用 `ContentCachingResponseWrapper` 包装响应，复制响应体到 Redis。重放时直接从 Redis 读 JSON 写回。响应体大小无硬限制，但建议 < 64KB（大响应不适合幂等缓存）。
