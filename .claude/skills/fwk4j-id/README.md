# framework4j-id

> 雪花 ID 生成（`SnowflakeDistributor`）+ OpenID 12 字符混淆（防遍历攻击）。

## 1. 概览

| 项 | 值 |
|---|---|
| 职责 | 分布式 ID 生成（雪花算法）/ `WorkerIdStrategy`（Redis 租约 / IP 哈希）/ OpenID 混淆（`Long` ↔ 12 字符串）/ MyBatis `IdentifierGenerator` 自动注册 |
| 配置前缀 | `framework4j.id.*`、`framework4j.openid.*` |
| 必需依赖 | `spring-boot-starter`、`mybatis-plus-spring-boot3-starter`（optional） |
| 可选依赖 | `framework4j-redis`（用 `RedisWorkerIdStrategy` 时必需）、`framework4j-api`（仅 test） |
| 在 SDK 中的位置 | 基础层，独立于 `datetime` / `redis` / `datasource` |

**核心原则**：内部用 `Long` 主键（雪花 ID，64 位），对外暴露 12 字符 OpenID 字符串（防遍历 + 防猜测）。前端永远拿不到原始 `Long`。

## 2. 快速开始

### Maven 依赖

```xml
<dependency>
    <groupId>fun.commons</groupId>
    <artifactId>framework4j-id</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 最小 application.yml

```yaml
framework4j:
  id:
    enabled: true
    worker-id-strategy: redis  # 或 ip-hash（无 Redis 时用）
    mybatis:
      enabled: true  # 自动注册 IdentifierGenerator
  openid:
    enabled: true
    salt: "${OPENID_SALT}"  # 必填，环境变量注入
```

### 最小代码示例

```java
@TableName("users")
public class UserDO {
    @TableId(type = IdType.ASSIGN_ID)  // MyBatis Plus 自动用雪花 ID
    private Long id;
    // ...
}

// Controller 永远返回 OpenID 字符串
public record UserVO(
    @OpenId Long id,           // 序列化为 12 字符串
    String name
) {}

@GetMapping("/v1/users/{open_id}")
public ApiResponse<UserVO> getUser(@PathVariable("open_id") String openId) {
    Long id = IdObfuscator.fromOpenId(openId);  // 12 字符串 → Long
    return ApiResponse.success(userService.find(id), TraceContext.getTraceId());
}
```

## 3. 配置参考

### `framework4j.id.*`

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.id.enabled` | `boolean` | `true` | 是否启用 |
| `framework4j.id.worker-id-strategy` | `String` | `redis` | `redis`（租约）/ `ip-hash`（无 Redis 兜底） |
| `framework4j.id.epoch` | `long` | `1704067200000`（2024-01-01 UTC+8） | 雪花纪元 |
| `framework4j.id.mybatis.enabled` | `boolean` | `true` | 自动注册 MyBatis Plus `IdentifierGenerator` |
| `framework4j.id.redis.lease-seconds` | `int` | `30` | WorkerId 租约时长 |
| `framework4j.id.redis.renew-interval` | `int` | `10` | 续约间隔 |

### `framework4j.openid.*`

| 属性 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `framework4j.openid.enabled` | `boolean` | `true` | 是否启用 |
| `framework4j.openid.salt` | `String` | 必填 | HMAC 盐值，环境变量注入 |
| `framework4j.openid.length` | `int` | `12` | 输出字符长度（11 数据 + 1 校验位） |
| `framework4j.openid.alphabet` | `String` | `0123456789ABCDEFGHJKMNPQRSTVWXYZ` | 字符表（去除易混字符） |

## 4. API 参考

### `SnowflakeDistributor`

```java
public class SnowflakeDistributor {
    public long nextId();              // 同步生成
    public long[] nextIds(int count);  // 批量
    public long getWorkerId();
    public long getEpoch();
}
```

### `WorkerIdStrategy`（接口）

```java
public interface WorkerIdStrategy {
    long getWorkerId();        // 0 ~ 1023
    void renew();              // 续约（Redis 模式）
}
```

实现：
- `RedisWorkerIdStrategy`：租约模式，多实例不冲突（推荐生产）
- `IpHashWorkerIdStrategy`：IP 哈希取模，无 Redis 兜底（适合开发）

### `IdObfuscator`

```java
public class IdObfuscator {
    public static String toOpenId(Long id);    // Long → 12 字符串
    public static Long fromOpenId(String s);    // 12 字符串 → Long
    public static boolean isValid(String s);    // 校验位验证
    public static String toOpenId(long id, String prefix);  // 带业务前缀
}
```

> v2.2 修正：原 README 写 `OpenID.encode/decode`（全大写类名 + encode/decode 方法名），与代码不一致。实际是 `IdObfuscator.toOpenId/fromOpenId`。若需 `OpenID` 门面类请提 issue。

### `@OpenId`（注解）

标注在 `Long` 字段上，Jackson 序列化时自动转 OpenID 字符串：

```java
public record OrderVO(
    @OpenId Long id,
    @OpenId Long userId,
    String amount
) {}
```

### `OpenIdTypeHandler`（MyBatis）

数据库字段是 12 字符串，Java 实体是 `Long`：

```java
@TableName("orders")
public class OrderDO {
    @TableId(type = IdType.INPUT)
    @TableField(typeHandler = OpenIdTypeHandler.class)
    private Long id;  // DB 存 "XK7M3N9PQRST"，Java 是 Long
}
```

## 5. 示例

### 5.1 主键生成

```java
@Service
public class OrderService {
    @Resource
    private SnowflakeDistributor snowflake;
    
    public Long createOrder(CreateOrderRequest req) {
        Long id = snowflake.nextId();
        // INSERT INTO orders (id, ...) VALUES (id, ...)
        return id;
    }
}
```

### 5.2 OpenID 全链路

```java
// Controller 入参：12 字符串
@GetMapping("/v1/users/{open_id}")
public ApiResponse<UserVO> getUser(@PathVariable("open_id") String openId) {
    Long id = IdObfuscator.fromOpenId(openId);  // 12 字符串 → Long
    UserDO user = userService.find(id);
    UserVO vo = new UserVO(user.getId(), user.getName());  // @OpenId 自动编码
    return ApiResponse.success(vo, TraceContext.getTraceId());
}
```

### 5.3 多实例部署（Redis WorkerId）

```yaml
framework4j:
  id:
    worker-id-strategy: redis
    redis:
      lease-seconds: 30
      renew-interval: 10
```

10 个实例同时启动，每个租约到不同的 `workerId`（0-9），保证 ID 不冲突。

## 6. 错误码

| Code | 名称 | 触发场景 |
|---|---|---|
| `10900` | `INTERNAL_ERROR` | Redis 连接失败导致 WorkerId 获取失败 |
| `10102` | `FORMAT_INVALID` | OpenID 格式不对（长度 / 校验位 / 字符表） |

## 7. FAQ

**Q1：为什么不用 UUID？**
A：UUID 128 位、无序、索引性能差。雪花 ID 64 位、时间有序、适合做 MySQL 主键。OpenID 12 字符串仅用于对外暴露，数据库仍存 `Long`。

**Q2：OpenID 能被逆向回 Long 吗？**
A：不能直接逆向（HMAC-SHA256 单向）。但同 salt 下，同一个 `Long` 永远映射到同一个 OpenID（确定性），所以应用层 `IdObfuscator.fromOpenId(openId)` 能还原。salt 泄露 = OpenID 失效，必须环境变量注入。

**Q3：雪花 ID 会重复吗？**
A：`RedisWorkerIdStrategy` 模式下不会（每个实例独立 `workerId`）。`IpHashWorkerIdStrategy` 模式下，同 IP 多实例会冲突（仅适合开发）。生产必须用 Redis 模式。

**Q4：`@OpenId` 标注的字段在前端怎么用？**
A：前端永远拿 12 字符串，传回时也是 12 字符串。前端不需要解析。详见 mc-webui-spec 场景三。

**Q5：数据库存 `Long` 还是 OpenID 字符串？**
A：存 `Long`（雪花 ID）。OpenID 仅在 API 层暴露。`OpenIdTypeHandler` 用于历史数据库已存字符串的场景。
