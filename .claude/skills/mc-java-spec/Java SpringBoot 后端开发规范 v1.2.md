# Java SpringBoot 后端开发规范 v1.2

> 版本：v1.2
> 修订日期：2026-06-18

## 1. 目的与总则

### 1.1 目的

本文档为基于 **Java 17 LTS + Spring Boot 3.2 LTS** 的后端应用开发提供统一的编码与配置规范。以《阿里巴巴 Java 开发手册》为基底，结合特定技术栈与项目要求，提升代码质量、可维护性、安全性与团队协作效率。

### 1.2 适用范围

所有使用 Java 17、Spring Boot 3.2 及以上版本进行开发的后端项目。**所有开发人员【强制】遵守本规范。**

### 1.3 核心技术栈

| 类别 | 选型 |
|---|---|
| JDK | Java 17 LTS（Amazon Corretto 或 OpenJDK） |
| Web 框架 | Spring MVC（spring-boot-starter-web） |
| Spring | Spring Boot 3.2 LTS |
| 构建工具 | Maven 3.8+ |
| ORM | MyBatis Plus |
| 连接池 | Druid |
| Redis 客户端 | Redisson（含 Spring RedisTemplate） |
| **JSON 库** | **Jackson**（Spring Boot 默认，v1.2 起统一） |
| 消息队列 | RocketMQ 5.x |
| API 文档 | OpenAPI 3.x（springdoc-openapi v2） |
| 链路追踪 | OpenTelemetry（推荐）/ Brave / SkyWalking（见 SDK 白名单 §6.3） |

### 1.4 JSON 库选型

 **Jackson**



## 2. 依赖与仓库管理规范

为落实「不在目录内的 SDK 不可以用、不在目录内的仓库地址不可以用」原则，【强制】采用「**统一父 POM** + **中央仓库代理**」模式。

### 2.1 统一父 POM

1. 所有项目【强制】继承公司统一维护的 `corporate-parent-pom.xml`。
2. 业务 `pom.xml` 引入依赖时【强制】**禁止**包含 `<version>` 标签。
3. 父 POM 的 `<dependencyManagement>` 节点【强制】定义所有「已报备、已审批」的依赖项。

### 2.2 统一仓库管理

1. 所有项目【强制】使用公司内部 Maven 仓库代理（Nexus / Artifactory）。
2. 业务 `pom.xml` 中【禁止】出现 `<repositories>` 或 `<pluginRepositories>` 标签。
3. 仓库代理负责管理「已报备」的上游仓库。

### 2.3 依赖报备流程

详见《Java SpringBoot 后端开发 SDK 白名单 v1.x》附录 B。

### 2.4 BOM 清单

父 POM【推荐】import 以下 BOM：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-dependencies</artifactId>
      <version>${spring-boot.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>com.baomidou</groupId>
      <artifactId>mybatis-plus-bom</artifactId>
      <version>${mybatis-plus.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>org.redisson</groupId>
      <artifactId>redisson-bom</artifactId>
      <version>${redisson.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-bom</artifactId>
      <version>${opentelemetry.version}</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

> Jackson 版本由 `spring-boot-dependencies` BOM 自动管理，无需手动声明。

### 2.5 许可证合规底线

| 协议 | 是否允许 | 说明 |
|---|---|---|
| Apache 2.0 / MIT / BSD | ✅ 允许 | 主流宽松协议 |
| MPL 2.0 / EPL 2.0 | ✅ 允许 | 弱传染，文件级 |
| LGPL | ⚠️ 限制 | 静态链接可用 |
| **GPL / AGPL / SSPL** | ❌ **禁止** | 传染性 / 商业风险 |
| 商业 License | ❌ **禁止** | 法律风险 |

## 3. Java 17 编码规范

### 3.1 【强制】使用 `jakarta.*` 命名空间

所有新代码【强制】使用 `jakarta.*` 包：

```java
import jakarta.persistence.*;
import jakarta.servlet.*;
import jakarta.validation.*;
import jakarta.annotation.*;
```

### 3.2 【强制】DTO/VO 与 Entity 的对象模型分类

| 类型 | 推荐方案 | 原因 |
|---|---|---|
| **DTO / VO / Request / Response** | `record`（首选）或 `@Value` | 不可变、线程安全、简洁 |
| **Entity（DO）** | `@Getter` + `@Setter`，**禁用 `@Data`** | MyBatis Plus 懒加载 + 双向关联 + `@Data` → 栈溢出 |
| **BO（业务对象）** | `record` 或 `@Getter` + `@Setter` | 视场景 |
| **Configuration Properties** | `@Getter` + `@Setter` | Spring 反射注入需要 setter |

```java
// ✅ Entity（禁 @Data）
@Getter @Setter
@ToString(exclude = {"items", "user"})
@TableName("trade_order")
public class OrderDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    // ...
}

// ✅ DTO/VO 用 record
public record OrderCreateRequest(
    @NotBlank String skuCode,
    @Min(1) @Max(999) Integer quantity
) {}

public record OrderDetailVO(
    String id,
    String totalAmount,
    Long createdAt
) {}
```

### 3.3 【推荐】审慎使用 `var`

```java
// ✅ 推荐（类型明确）
var order = new OrderDO();
var items = new ArrayList<OrderItem>();

// ❌ 禁止（类型不明确）
var result = orderService.process(order);
```

### 3.4 【强制】正确使用 `Optional`

1. 【禁止】用作类字段或方法参数
2. 【禁止】直接调用 `.get()`
3. 必须用 `isPresent()` / `orElse()` / `orElseThrow()` / `ifPresent()`

```java
// ✅
OrderDO order = orderRepo.findById(id)
    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND));

// ❌
OrderDO order = orderRepo.findById(id).get();
```

### 3.5 【推荐】Stream API

【推荐】用于集合转换、过滤、聚合；【禁止】在 `map`/`filter` 内执行 DB 查询、RPC 等副作用操作。

### 3.6 【强制】阿里约规核心条款

#### 3.6.1 魔法值禁止

代码中【禁止】出现硬编码的数字、字符串字面值（除 `0`/`1`/`""`），必须定义为常量或枚举：

```java
// ❌
if (order.getStatus() == 5) { ... }

// ✅
if (Objects.equals(order.getStatus(), OrderStatus.CANCELLED.getCode())) { ... }
```

#### 3.6.2 字面值规范

| 类型 | 规则 | 正例 | 反例 |
|---|---|---|---|
| `long` | 大写 `L` | `1000L` | ❌ `1000l` |
| `float` | 大写 `F` | `3.14F` | ❌ `3.14f` |
| 十六进制 | 大写 `0x` + 大写字母 | `0xFF` | ❌ `0Xff` |

#### 3.6.3 控制语句必须加大括号

`if` / `else` / `for` / `while` / `do`【强制】加大括号，即使单行。

#### 3.6.4 行宽限制

- 单行代码 ≤ **120 字符**
- 单行注释 ≤ **120 字符**
- 长表达式换行时运算符放行尾

#### 3.6.5 包装类比较

【禁止】用 `==` 比较包装类，必须 `Objects.equals()` 或 `.equals()`：

```java
// ❌ 拆箱陷阱 + 缓存边界
if (order.getUserId() == userId) { ... }

// ✅
if (Objects.equals(order.getUserId(), userId)) { ... }
```

#### 3.6.6 集合判空

【强制】用 `CollectionUtils.isEmpty()`，禁止 `null` 比较：

```java
// ❌
if (list != null && list.size() > 0) { ... }

// ✅
if (!CollectionUtils.isEmpty(list)) { ... }
```

#### 3.6.7 线程安全

| 类（非线程安全） | 替代方案 |
|---|---|
| `SimpleDateFormat` | `DateTimeFormatter` |
| `HashMap` / `ArrayList` / `StringBuilder` | `ConcurrentHashMap` / `CopyOnWriteArrayList` / `StringBuffer` 或局部变量 |

#### 3.6.8 线程池创建规范

【禁止】用 `Executors.newXxx()`，【强制】用 `ThreadPoolExecutor` 显式构造：

```java
ThreadPoolExecutor executor = new ThreadPoolExecutor(
    8, 16, 60L, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(1000),
    new ThreadFactoryBuilder()
        .setNameFormat("order-pool-%d")
        .setUncaughtExceptionHandler((t, e) -> log.error("线程异常", e))
        .build(),
    new ThreadPoolExecutor.CallerRunsPolicy()
);
```

线程池必须配置**业务命名**（便于 jstack 排查）。

#### 3.6.9 ThreadLocal 使用规范

【强制】`finally` 中 `remove()`，防线程池复用导致的内存泄漏与用户身份串号。

#### 3.6.10 `equals` 与 `hashCode`

- 自定义类用作 `HashSet` / `HashMap` key 时【强制】重写
- 推荐 `Objects.hash()` + `Objects.equals()`

## 4. Spring Boot 3.2 规范

### 4.1 【强制】`@ConfigurationProperties` 正确用法

**正确写法**（二选一）：

```java
// 方式 A：组件 + 配置属性（推荐）
@Component
@ConfigurationProperties(prefix = "app.security")
@Getter @Setter
public class SecurityProperties {
    private String jwtSecret;
    private long tokenValiditySeconds = 3600;
}

// 方式 B：通过 @EnableConfigurationProperties 启用
@ConfigurationProperties(prefix = "app.security")
@Getter @Setter
public class SecurityProperties { ... }

@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig { ... }
```

【禁止】用 `@Value` 散落注入。

### 4.2 【强制】全局异常处理（v1.2 重写：使用 ErrorCode enum）

完全采用 mc-api-spec v1.6 信封。所有异常返回 HTTP **200**，业务错误码放入 body。

#### 4.2.1 `BizException` 定义

```java
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;
    private final List<ApiError> errors;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getDescription());
        this.errorCode = errorCode;
        this.errors = null;
    }

    public BizException(ErrorCode errorCode, String customMessage) {
        super(customMessage);
        this.errorCode = errorCode;
        this.errors = null;
    }

    public BizException(ErrorCode errorCode, List<ApiError> errors) {
        super(errorCode.getDescription());
        this.errorCode = errorCode;
        this.errors = errors;
    }

    public int getCode() {
        return errorCode.getCode();
    }
}
```

#### 4.2.2 全局异常处理器

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        String traceId = TraceContext.current();
        log.warn("[traceId={}] BizException code={}, msg={}",
                 traceId, e.getCode(), e.getMessage());
        return buildResponse(ApiResponse.fail(e.getErrorCode(), e.getMessage(), e.getErrors(), traceId), traceId);
    }

    /** 参数校验失败：10100 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String traceId = TraceContext.current();
        List<ApiError> errors = e.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ApiError(
                fe.getField(),
                inferSubCode(fe),                       // 子类型，如 FORMAT_INVALID
                fe.getDefaultMessage(),
                fe.getRejectedValue()
            ))
            .toList();
        return buildResponse(
            ApiResponse.fail(ErrorCode.INVALID_PARAMS, "请求参数错误", errors, traceId),
            traceId
        );
    }

    /** 缺少请求体 / InvalidRequest：10100 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadJson(HttpMessageNotReadableException e) {
        String traceId = TraceContext.current();
        log.warn("[traceId={}] Bad request body", traceId, e);
        return buildResponse(
            ApiResponse.fail(ErrorCode.BODY_INVALID, "请求体格式错误", null, traceId),
            traceId
        );
    }

    /** 兜底：10001 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception e) {
        String traceId = TraceContext.current();
        log.error("[traceId={}] Unhandled exception", traceId, e);
        return buildResponse(
            ApiResponse.fail(ErrorCode.SYSTEM_BUSY, "系统繁忙，请稍后再试", null, traceId),
            traceId
        );
    }

    private ResponseEntity<ApiResponse<Void>> buildResponse(ApiResponse<Void> body, String traceId) {
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(body);
    }

    private String inferSubCode(FieldError fe) {
        if (fe.getCode() != null) {
            if (fe.getCode().contains("NotNull") || fe.getCode().contains("NotBlank")) {
                return "REQUIRED_MISSING";
            }
            if (fe.getCode().contains("Pattern") || fe.getCode().contains("Email")) {
                return "FORMAT_INVALID";
            }
            if (fe.getCode().contains("Size") || fe.getCode().contains("Length")) {
                return "LENGTH_INVALID";
            }
            if (fe.getCode().contains("Min") || fe.getCode().contains("Max")) {
                return "RANGE_INVALID";
            }
        }
        return null;
    }
}
```

### 4.3 【强制】事务管理（v1.2 补 readOnly 警告）

| 规则 | 说明 |
|---|---|
| `@Transactional` 必须 public | 非 public 不生效 |
| **必须**指定 `rollbackFor = Exception.class` | 默认只回滚 RuntimeException |
| 只读查询用 `readOnly = true` | PG 走副本 / Hibernate FlushMode=MANUAL；**MySQL InnoDB 几乎无性能收益，仅作意图声明** |
| 避免大事务 | 只包裹必要的 DB 写操作；RPC、HTTP、文件 IO 不进事务 |
| 警惕自调用 | `this.method()` 绕过 AOP 代理，事务失效 |
| 只在 Service 层 | Controller 禁用 |
| 默认传播 `REQUIRED` | 调用方有事务则加入，无则新建 |

```java
// ✅ 写事务
@Override
@Transactional(rollbackFor = Exception.class)
public String createOrder(OrderCreateRequest req) {
    orderMapper.insert(order);
    itemMapper.insertBatch(items);
    return order.getId().toString();
}

// ✅ 只读（PG 场景效果明显）
@Override
@Transactional(readOnly = true, rollbackFor = Exception.class)
public OrderDetailVO getOrderById(String id) { ... }
```

### 4.4 ORM (MyBatis Plus) 与连接池规范

#### 4.4.1 【强制】Mapper 接口继承 `BaseMapper`

```java
public interface OrderMapper extends BaseMapper<OrderDO> {
    // 复杂查询走 XML
}
```

#### 4.4.2 【强制】`application.yml` 必须开启驼峰映射

```yaml
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true   # DB 下划线 ↔ Java 驼峰 自动转换
  global-config:
    db-config:
      logic-delete-field: is_deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

未开启会导致 `LambdaQueryWrapper`（如 `OrderDO::getCreatedAt`）映射不到 DB 列 `created_at`。

#### 4.4.3 【强制】实体类 `@TableName` / `@TableField`

```java
@TableName("trade_order")
public class OrderDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("order_no")
    private String orderNo;

    @TableField(value = "attributes", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> attributes;   // JSONB 字段
}
```

> **v1.2 变更**：JSONB TypeHandler 由 fastjson2 的 `JsonTypeHandler` 改为 **MyBatis Plus 自带的 `JacksonTypeHandler`**（`com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler`），与全局 JSON 库统一。

#### 4.4.4 【强制】Druid 完整配置（v1.2 合并连接池 + 监控 + 慢 SQL）

```yaml
spring:
  datasource:
    type: com.alibaba.druid.pool.DruidDataSource
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}
    username: ${DB_USER}
    password: ${DB_PASSWORD}     # 必须环境变量注入
    druid:
      # === 连接池核心参数 ===
      initial-size: 5
      min-idle: 5
      max-active: 20             # 按业务压测调
      max-wait: 5000             # 获取连接超时 ms
      time-between-eviction-runs-millis: 60000
      min-evictable-idle-time-millis: 300000
      validation-query: SELECT 1
      test-while-idle: true
      test-on-borrow: false
      test-on-return: false
      # === 连接泄漏检测 ===
      remove-abandoned: true
      remove-abandoned-timeout: 300
      log-abandoned: true
      # === 监控页面（生产严格管控）===
      stat-view-servlet:
        enabled: true
        url-pattern: /druid/*
        login-username: ${DRUID_USER}
        login-password: ${DRUID_PASSWORD}
        allow: 10.0.0.0/8,172.16.0.0/12   # VPN / 内网白名单
        deny: ""
      web-stat-filter:
        enabled: true
        url-pattern: /*
        exclusions: "*.js,*.gif,*.jpg,*.bmp,*.png,*.css,*.ico,/druid/*"
      filter:
        stat:
          enabled: true
          slow-sql-millis: 1000          # 默认；见下方 Profile 覆盖
          log-slow-sql: true
        wall:
          enabled: true
          config:
            comment-allowed: false       # 关闭注释暴露 metadata
```

**Profile 区分慢 SQL 阈值**（v1.2 新增）：

```yaml
# application-dev.yml
spring:
  datasource:
    druid:
      filter:
        stat:
          slow-sql-millis: 200           # 开发环境更严格

# application-prod.yml
spring:
  datasource:
    druid:
      filter:
        stat:
          slow-sql-millis: 2000          # 生产环境宽松，避免噪音
```

**安全红线**：

- 监控页【禁止】默认 `admin/admin` 凭证
- 生产环境【必须】配置 IP 白名单（VPN / 内网）
- 监控页【不应】暴露公网
- `wall` filter 关闭 metadata 暴露

### 4.5 Redis (Redisson) 规范

| 规则 | 说明 |
|---|---|
| 简单 K-V 缓存 | 用 `StringRedisTemplate`，与 `@Cacheable` 集成 |
| 高级功能 | 注入 `RedissonClient`，优先用 `RMap` / `RSet` / `RLock` |
| 分布式锁 | 必须 `try { lock(); ... } finally { lock.unlock(); }` |
| Key 命名 | `[业务]:[子模块]:[ID]`，**Key 前缀必须定义为常量**（避免魔法字符串） |
| TTL | 所有 Key 必须设过期时间 |

```java
// ✅ Key 前缀常量化（防魔法值）
public static final String ORDER_LOCK_PREFIX = "order:lock:";
public static final String USER_CACHE_PREFIX = "user:cache:profile:";

// ✅ 分布式锁标准写法
String lockKey = ORDER_LOCK_PREFIX + orderId;
RLock lock = redissonClient.getLock(lockKey);
boolean acquired = false;
try {
    acquired = lock.tryLock(3, 30, TimeUnit.SECONDS);
    if (!acquired) {
        throw new BizException(ErrorCode.LOCK_BUSY);
    }
    // 业务逻辑
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new BizException(ErrorCode.SYSTEM_BUSY, "操作中断");
} finally {
    if (acquired && lock.isHeldByCurrentThread()) {
        lock.unlock();
    }
}
```

### 4.6 JSON (Jackson) 规范（v1.2 全面重写）

#### 4.6.1 【强制】全局配置：snake_case + Long-to-String + Java 8 时间

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /**
     * 全局 Jackson 定制：
     * 1. snake_case 命名（对齐 v1.6 §5.1）
     * 2. Long / long 序列化为 String（防 JS 精度丢失，对齐 v1.6 §5.2）
     * 3. Java 8 时间类型支持
     * 4. 忽略未知字段（向前兼容）
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            // 1. 全局 snake_case
            builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

            // 2. Long → String（ID 类字段防精度丢失）
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);

            // 3. Java 8 时间
            builder.modulesToInstall(new JavaTimeModule());

            // 4. 忽略反序列化时的未知字段
            builder.failOnUnknownProperties(false);

            // 5. 不输出 null 字段（与 v1.6 §5.3 一致，省略不存在的字段）
            // 注意：信封的 data/error 由 ApiResponse record 控制，本身始终出现
        };
    }
}
```

> 💡 **效果**：Java `private Long userId` → 序列化为 `"user_id": "12345"`；Java `private String orderNo` → `"order_no": "..."`。**一行配置，全部生效，DTO 零注解**。

#### 4.6.2 【强制】金额序列化（对齐 v1.6 §5.2）

金额字段在 Service 层用 `BigDecimal`，输出时统一格式化为 String 元 2 位小数：

```java
public final class MoneyUtils {
    private MoneyUtils() {}

    public static String format(BigDecimal amount) {
        if (amount == null) return null;
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}

// DTO 中金额字段直接 String，由 Converter 调用 MoneyUtils.format 转换
public record OrderDetailVO(
    String id,
    String totalAmount,        // 由 BigDecimal.format 后传入
    String currency,
    Long createdAt
) {}
```

如希望 DTO 内部仍用 `BigDecimal`，可用 Jackson 自定义序列化器：

```java
public class MoneySerializer extends JsonSerializer<BigDecimal> {
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider sp) throws IOException {
        gen.writeString(MoneyUtils.format(value));
    }
}

// 字段标注
@JsonSerialize(using = MoneySerializer.class)
private BigDecimal totalAmount;
```

#### 4.6.3 【强制】枚举序列化（字符串字面值）

```java
public enum OrderStatus {
    PENDING, PAID, SHIPPED, COMPLETED, CANCELLED
}

// 默认 Jackson 输出 "PAID"（字符串字面值）✓
```

如枚举含 code/description 字段且需对外暴露 code：

```java
public enum OrderStatus {
    PENDING(1, "待支付"),
    PAID(2, "已支付");

    @JsonValue
    private final int code;       // 输出数字 code
    private final String description;
    // ...
}
```

> **推荐**：对外枚举优先用字符串字面值（`"PAID"`），符合 mc-api-spec v1.0 §3.5「枚举值用 String，禁止数字」。

#### 4.6.4 【推荐】开发环境美化输出

```java
@Bean
@Profile("dev")
public Jackson2ObjectMapperBuilderCustomizer devJacksonCustomizer() {
    return builder -> builder.featuresToEnable(SerializationFeature.INDENT_OUTPUT);
}
```

> **禁止**：把 `INDENT_OUTPUT` 配在生产 profile，会增大响应体 30%+ 并拖慢吞吐。

#### 4.6.5 【强制】禁用危险特性

```java
// 禁止：允许注释（攻击者可注入 /* */ 隐藏字段）
builder.featuresToDisable(JsonParser.Feature.ALLOW_COMMENTS);

// 禁止：重复字段静默接受
builder.featuresToDisable(JsonReadFeature.ALLOW_DUPLICATE_KEYS.mappedFeature());
```

## 5. API 响应层（对齐 mc-api-spec v1.6）

### 5.1 【强制】`ApiResponse<T>` 信封

```java
import com.fasterxml.jackson.annotation.JsonProperty;

public record ApiResponse<T>(
    @JsonProperty("code") int code,
    @JsonProperty("message") String message,
    @JsonProperty("data") T data,
    @JsonProperty("error") List<ApiError> error,
    @JsonProperty("trace_id") String traceId,
    @JsonProperty("timestamp") long timestamp
) {
    public static <T> ApiResponse<T> success(T data, String traceId) {
        return new ApiResponse<>(0, "success", data, null, traceId, System.currentTimeMillis());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message,
                                          List<ApiError> errors, String traceId) {
        return new ApiResponse<>(errorCode.getCode(), message, null, errors, traceId, System.currentTimeMillis());
    }
}

public record ApiError(
    String field,        // snake_case
    String code,         // 子类型：FORMAT_INVALID 等
    String message,
    Object value
) {}
```

> 即使全局 snake_case 已开，信封字段建议**显式 `@JsonProperty`** 锁定字段名，防止后续配置变更引起契约破坏。

### 5.2 Controller 标准模板

```java
@Tag(name = "Order", description = "订单管理")
@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final TraceContext traceContext;

    @Operation(
        summary = "查询订单详情",
        operationId = "getOrderById",
        description = "根据订单 ID 查询订单完整信息。"
    )
    @GetMapping("/{order_id}")
    public ResponseEntity<ApiResponse<OrderDetailVO>> getOrder(
        @Parameter(description = "订单 ID", required = true, example = "892310293123123")
        @PathVariable("order_id") String orderId    // ✅ String
    ) {
        OrderDetailVO vo = orderService.getOrderById(orderId);
        String traceId = traceContext.current();
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(ApiResponse.success(vo, traceId));
    }

    @Operation(summary = "创建订单", operationId = "createOrder")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderCreateResponse>> create(
        @RequestBody @Valid OrderCreateRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        OrderCreateResponse resp = orderService.create(request, idempotencyKey);
        String traceId = traceContext.current();
        return ResponseEntity.ok()
            .header("X-Trace-Id", traceId)
            .body(ApiResponse.success(resp, traceId));
    }
}
```

### 5.3 ID 与金额序列化（对齐 v1.6 §5.2，v1.2 补 Long 桥接）

```java
// ✅ DTO 中 ID 字段类型用 String（推荐）
public record OrderDetailVO(
    String id,                  // 由 Service 层 Long.toString() 转换
    String orderNo,
    String userId,
    String totalAmount,         // 由 MoneyUtils.format 转换
    String status,
    Long createdAt              // 时间戳 Long ms
) {}

// ✅ 或 DTO 中保留 Long，靠 §4.6.1 全局 ToStringSerializer 自动转换
public record OrderDetailVO(
    Long id,                    // 序列化时自动变 "id": "..."
    String orderNo,
    Long userId,                // 自动变 "user_id": "..."
    Long createdAt
) {}

// ✅ Service 转换
public OrderDetailVO toVO(OrderDO order) {
    return new OrderDetailVO(
        order.getId(),          // Long → String 由 Jackson 自动处理
        order.getOrderNo(),
        order.getUserId(),
        MoneyUtils.format(order.getTotalAmount()),  // BigDecimal → String
        order.getStatus().name(),
        order.getCreatedAt()
    );
}
```

### 5.4 分页实现（v1.2 `PageResponse` 改 record）

```java
public record PageResponse<T>(
    List<T> list,
    Long total,
    Integer page,
    Integer pageSize,
    Boolean hasMore,
    Object summary
) {}

// Service 实现
@Override
@Transactional(readOnly = true, rollbackFor = Exception.class)
public PageResponse<OrderListItemVO> listOrders(OrderQueryRequest req) {
    Page<OrderDO> page = new Page<>(req.getPage(), req.getPageSize());
    LambdaQueryWrapper<OrderDO> wrapper = Wrappers.<OrderDO>lambdaQuery()
        .eq(req.getStatus() != null, OrderDO::getStatus, req.getStatus())
        .ge(req.getCreatedAfter() != null, OrderDO::getCreatedAt, req.getCreatedAfter())
        .orderByDesc(OrderDO::getCreatedAt);

    IPage<OrderDO> result = orderMapper.selectPage(page, wrapper);

    List<OrderListItemVO> list = result.getRecords().stream()
        .map(OrderConverter::toListItemVO)
        .toList();

    return new PageResponse<>(
        list,
        result.getTotal(),
        (int) result.getCurrent(),
        (int) result.getSize(),
        result.getCurrent() < result.getPages(),
        null    // summary 按需返回
    );
}
```

> 游标 / 键集分页：详见 mc-api-spec v1.6 §6.3、§6.4。

### 5.5 `ErrorCode` enum（v1.6 §7 的实现参考）

> **权威定义在 v1.6 §7**；本节为 Java 侧实现参考。新增错误码必须先在 v1.6 §7 登记，再同步本 enum。

```java
import lombok.Getter;

@Getter
public enum ErrorCode {
    // 系统类（10xxx）
    SYSTEM_BUSY       (10001, "系统繁忙，请稍后再试"),
    SERVICE_MAINTAIN  (10002, "服务暂停维护"),
    SERVICE_TIMEOUT   (10003, "服务调用超时"),
    THIRD_PARTY_ERROR (10004, "第三方服务异常"),
    MIDDLEWARE_ERROR  (10005, "中间件服务异常"),

    // 参数类（101xxx）
    INVALID_PARAMS    (10100, "请求参数错误"),
    REQUIRED_MISSING  (10101, "必填参数缺失"),
    FORMAT_INVALID    (10102, "参数格式错误"),
    BODY_INVALID      (10103, "请求体格式错误"),

    // 认证类（102xxx）
    UNAUTHORIZED      (10200, "用户未登录"),
    TOKEN_EXPIRED     (10201, "登录凭证已过期"),
    TOKEN_INVALID     (10202, "登录凭证无效"),

    // 权限类（103xxx）
    FORBIDDEN         (10300, "无权限访问"),
    DATA_FORBIDDEN    (10301, "数据权限不足"),

    // 资源类（104xxx）
    NOT_FOUND         (10400, "请求资源不存在"),
    UNIQUE_CONFLICT   (10401, "数据已存在"),
    STATE_CONFLICT    (10402, "数据状态冲突"),
    LOCK_BUSY         (10403, "数据被锁定"),

    // 流量类（105xxx）
    RATE_LIMITED      (10500, "请求过于频繁"),
    IDEMPOTENT_CONFLICT (10501, "请勿重复提交"),

    // 业务混合（107xxx）
    PARTIAL_SUCCESS   (10700, "部分操作失败");

    private final int code;
    private final String description;

    ErrorCode(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
```

### 5.6 对象转换规范（v1.2 新增）

Service 层产出 BO / DO，需转换为对外 VO。三种方案：

| 方案 | 推荐场景 | 反例 |
|---|---|---|
| **手写 static 方法**（record 配合） | 字段 ≤ 10，转换逻辑清晰 | 字段超多时样板代码爆炸 |
| **MapStruct**（编译期生成） | 字段多、跨多对象聚合 | - |
| 反射 `BeanUtils.copyProperties` | **禁用** | 性能差 + 类型不安全 + 字段名不匹配静默失败 |

```java
// ✅ 手写 Converter
public final class OrderConverter {
    private OrderConverter() {}

    public static OrderListItemVO toListItemVO(OrderDO order) {
        return new OrderListItemVO(
            order.getId().toString(),
            order.getOrderNo(),
            order.getStatus().name(),
            MoneyUtils.format(order.getTotalAmount()),
            order.getCreatedAt()
        );
    }
}

// ✅ MapStruct（字段多时）
@Mapper(componentModel = "spring", uses = MoneyUtils.class)
public interface OrderMapper {
    OrderDetailVO toDetailVO(OrderDO order);
}
```

## 6. 日志规范

### 6.1 【强制】SLF4J 占位符

```java
// ✅ 占位符（性能好 + 自动 toString）
log.info("Order created, id={}, userId={}", orderId, userId);

// ❌ 字符串拼接
log.info("Order created, id=" + orderId + ", userId=" + userId);
```

### 6.2 【强制】日志级别

| 级别 | 场景 |
|---|---|
| `ERROR` | 系统异常、第三方调用失败、数据不一致；必须通知运维 |
| `WARN` | 业务异常、可恢复异常、降级触发 |
| `INFO` | 关键业务节点（订单创建、支付成功、状态流转）；可供审计 |
| `DEBUG` | 开发调试，生产默认关闭 |
| `TRACE` | 极细粒度跟踪 |

### 6.3 【强制】异常日志必须带堆栈

```java
// ✅ 第二参数传 Exception，自动打印堆栈
log.error("[traceId={}] Unhandled exception", traceId, e);

// ❌ 失去堆栈
log.error("[traceId={}] Exception: " + e.getMessage(), traceId);
```

### 6.4 【强制】敏感信息脱敏

| 字段 | 脱敏方式 |
|---|---|
| 密码 / token / API key | 完全隐藏或 `***` |
| 身份证号 | 前 6 后 4：`110101********1234` |
| 手机号 | 前 3 后 4：`138****1234` |
| 银行卡号 | 前 4 后 4：`6228******5678` |
| 邮箱 | `a***@example.com` |

```java
public final class MaskUtils {
    private MaskUtils() {}

    public static String phone(String p) {
        if (p == null || p.length() < 7) return "***";
        return p.substring(0, 3) + "****" + p.substring(p.length() - 4);
    }

    public static String idCard(String id) {
        if (id == null || id.length() < 10) return "***";
        return id.substring(0, 6) + "********" + id.substring(id.length() - 4);
    }
}

// 使用
log.info("User login, phone={}", MaskUtils.phone(user.getPhone()));
```

### 6.5 【推荐】结构化日志（生产）

```xml
<!-- logback-spring.xml -->
<encoder class="net.logstash.logback.encoder.LogstashEncoder">
  <includeMdc>true</includeMdc>
  <customFields>{"app":"order-service"}</customFields>
</encoder>
```

### 6.6 【强制】TraceContext 实现（v1.2 补全）

`traceId` 是规范中的贯穿性字段（出现在 v1.6 信封、Header、日志 MDC、SQL 注释、异常处理）。落地必须一致。

#### 6.6.1 TraceContext 工具类

```java
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class TraceContext {

    public static final String TRACE_ID_KEY = "trace_id";

    /** 获取当前 traceId；不存在则生成 */
    public String current() {
        String traceId = MDC.get(TRACE_ID_KEY);
        if (traceId == null || traceId.isEmpty()) {
            traceId = generate();
            MDC.put(TRACE_ID_KEY, traceId);
        }
        return traceId;
    }

    /** 进入请求时设置 traceId（Filter 调用） */
    public void set(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            traceId = generate();
        }
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /** 请求结束时清理（防线程池复用串号） */
    public void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

    /** 生成 16 位 ULID 风格 traceId（推荐用 ULID 库，此处简化） */
    private String generate() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
```

#### 6.6.2 TraceFilter（请求入口注入）

```java
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceFilter implements Filter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final TraceContext traceContext;

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        // 1. 客户端透传优先，否则生成
        String traceId = httpReq.getHeader(TRACE_ID_HEADER);
        traceContext.set(traceId);

        // 2. 响应头必返（与 body trace_id 一致）
        httpResp.setHeader(TRACE_ID_HEADER, traceContext.current());

        try {
            chain.doFilter(req, resp);
        } finally {
            traceContext.clear();    // 防线程池串号
        }
    }
}
```

#### 6.6.3 logback 注入 trace_id

```xml
<!-- pattern 中带 %X{trace_id} -->
<pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] [%X{trace_id}] %-5level %logger{40} - %msg%n</pattern>
```

#### 6.6.4 MyBatis SQL 注释（与 v1.6 §4.1 一致）

```yaml
mybatis-plus:
  configuration:
    # 拦截器在 SQL 末尾追加 /*traceid=xxx*/ 注释
    default-statement-timeout: 30
  global-config:
    enable-sql-runner: true
```

或在自研 MyBatis Interceptor 中注入：

```java
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare",
                        args = {Connection.class, Integer.class})})
public class TraceSqlInterceptor implements Interceptor {
    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        BoundSql boundSql = handler.getBoundSql();
        String sql = boundSql.getSql();
        String traceId = MDC.get(TraceContext.TRACE_ID_KEY);
        if (traceId != null) {
            // 追加 SQL 注释，DBA 排查时可见
            Field sqlField = boundSql.getClass().getDeclaredField("sql");
            sqlField.setAccessible(true);
            sqlField.set(boundSql, sql + " /*traceid=" + traceId + "*/");
        }
        return invocation.proceed();
    }
}
```

> **替代方案**：若公司使用 OpenTelemetry / SkyWalking，TraceContext 可改为从 `Tracer.currentSpan().context().traceId()` 读取，但 API 接口保持不变。

## 7. 安全规范

### 7.1 Druid 监控访问控制

详见 §4.4.4。

### 7.2 SQL 注入防护

| 场景 | 推荐方案 |
|---|---|
| MyBatis Plus Wrapper | `eq` / `like` 等天然参数化 |
| 自定义 XML | 必须 `#{}`，禁止 `${}` |
| 排序字段动态化 | 服务端白名单校验 |

```xml
<!-- ❌ -->
<select id="search">
  SELECT * FROM orders WHERE status = '${status}'
  ORDER BY ${sortField} ${sortDir}
</select>

<!-- ✅ -->
<select id="search">
  SELECT * FROM orders WHERE status = #{status}
  ORDER BY
  <choose>
    <when test="sortField == 'created_at'">created_at</when>
    <when test="sortField == 'updated_at'">updated_at</when>
    <otherwise>created_at</otherwise>
  </choose>
</select>
```

### 7.3 JWT 配置规范

| 字段 | 必填 | 规范 |
|---|---|---|
| `iss` (issuer) | 是 | 签发方标识 |
| `sub` (subject) | 是 | 用户 ID（String） |
| `aud` (audience) | 是 | 接收方标识，校验时必须匹配 |
| `exp` (expiration) | 是 | 过期时间戳（秒）；access_token ≤ 2h，refresh_token ≤ 30d |
| `iat` (issued at) | 是 | 签发时间戳 |
| `jti` (JWT ID) | 推荐 | 唯一 ID，用于撤销与防重放 |

- 密钥长度 ≥ 256 位（HS256），生产用 RS256 / ES256
- 密钥【强制】环境变量注入，禁止硬编码
- 推荐密钥管理：HashiCorp Vault / K8s Secret / 云 KMS
- JWKS 轮换：每 90 天轮换签名密钥

### 7.4 CSRF / XSS

| 场景 | 防护 |
|---|---|
| 前后端分离 + JWT | 通常不需要 CSRF Token |
| Cookie 鉴权 | 必须 CSRF Token 或 `SameSite=Strict` |
| 用户输入存储 | 服务端转义或前端 `textContent` 渲染 |
| 富文本 | 用 OWASP Java HTML Sanitizer |

## 8. 单元测试规范

### 8.1 【强制】命名

`<Method>_Should_<Behavior>_When_<Condition>`：

```java
@Test
void createOrder_Should_ThrowBizException_When_StockInsufficient() { ... }

@Test
void getById_Should_ReturnOrder_When_OrderExists() { ... }
```

### 8.2 【强制】覆盖度门槛

| 类型 | 行覆盖 | 分支覆盖 |
|---|---|---|
| Service（业务逻辑） | ≥ 80% | ≥ 70% |
| Util / Helper | ≥ 90% | ≥ 80% |
| Controller | ≥ 50% | - |

通过 JaCoCo 在 CI 阶段强制：

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <executions>
    <execution>
      <id>check</id>
      <goals><goal>check</goal></goals>
      <configuration>
        <rules>
          <rule>
            <element>BUNDLE</element>
            <limits>
              <limit><counter>LINE</counter><minimum>0.80</minimum></limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### 8.3 数据库测试选型

| 方案 | 适用 |
|---|---|
| **Testcontainers + PG** | 涉及 JSONB / 复杂 SQL / 迁移脚本（**推荐主选**） |
| H2 内存 | 纯单元测试 |

```java
@Testcontainers
@SpringBootTest
class OrderMapperIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
    }
}
```

### 8.4 【强制】断言用 AssertJ

```java
// ✅
assertThat(orders)
    .hasSize(3)
    .extracting(OrderDO::getStatus)
    .containsExactly(OrderStatus.PAID, OrderStatus.PENDING, OrderStatus.CANCELLED);

// ❌
assertTrue(orders.size() == 3);
```

### 8.5 【推荐】ArchUnit 架构约束

```java
@Test
void services_should_not_depend_on_controllers() {
    classes().that().resideInAPackage("..service..")
        .should().onlyDependOnClassesThat()
        .resideInAnyPackage("..service..", "..mapper..", "..model..", "java..")
        .check(new ClassFileImporter().importPackages("com.company"));
}

@Test
void mappers_should_not_be_used_in_controllers() {
    noClasses().that().resideInAPackage("..controller..")
        .should().dependOnClassesThat().resideInAPackage("..mapper..")
        .check(new ClassFileImporter().importPackages("com.company"));
}
```

## 9. 性能规范

### 9.1 【强制】N+1 查询检测

```java
// ❌ N+1
for (OrderDO order : orders) {
    UserDO user = userMapper.selectById(order.getUserId());
}

// ✅ 批量
Set<Long> userIds = orders.stream().map(OrderDO::getUserId).collect(Collectors.toSet());
Map<Long, UserDO> userMap = userMapper.selectByIds(userIds).stream()
    .collect(Collectors.toMap(UserDO::getId, Function.identity()));
```

### 9.2 【强制】批量操作大小

| 操作 | 上限 |
|---|---|
| MyBatis Plus `insertBatch` | 1000 |
| Mapper XML `foreach` | 1000 |
| IN 查询列表 | 1000（PG 推荐用 `= ANY(?)`） |
| 同步批量业务接口 | 100 |
| > 100 | 必须异步 Job（v1.6 §7.11.1） |

### 9.3 【推荐】缓存策略

```java
// Cache-Aside 标准模板
public OrderDetailVO getOrder(String id) {
    String key = ORDER_CACHE_PREFIX + id;
    OrderDetailVO cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return cached;

    OrderDO order = orderMapper.selectById(id);
    if (order == null) throw new BizException(ErrorCode.NOT_FOUND);

    OrderDetailVO vo = OrderConverter.toDetailVO(order);
    redisTemplate.opsForValue().set(key, vo, 30, TimeUnit.MINUTES);
    return vo;
}
```

### 9.4 【强制】慢 SQL 监控

Druid 慢 SQL 阈值按 Profile（见 §4.4.4），生产 2s 告警钉钉 / Slack。

### 9.5 【推荐】连接池调优要点

- `max-active` 按 QPS × 平均 SQL 耗时计算，公式：`max-active ≈ QPS × avgSqlMs / 1000`
- `max-wait` 建议 ≤ 5s，超时立即抛异常而非阻塞堆积
- 启用 `remove-abandoned` 检测连接泄漏

## 10. 配置管理规范（v1.2 新增）

### 10.1 【强制】配置文件分层

```
src/main/resources/
├── application.yml              # 公共配置（profile 无关）
├── application-dev.yml          # 开发环境
├── application-test.yml         # 测试环境
├── application-pre.yml          # 预发环境
└── application-prod.yml         # 生产环境
```

激活方式：

```bash
java -jar app.jar --spring.profiles.active=prod
# 或环境变量
SPRING_PROFILES_ACTIVE=prod
```

### 10.2 【强制】敏感项不入仓

以下配置【禁止】明文写入 `application-*.yml`，必须通过环境变量 / Secret 管理：

- 数据库密码、Redis 密码、MQ 密码
- JWT 密钥、加密盐
- 第三方 API Key（阿里云 AK/SK、微信支付证书密码）
- Druid 监控页凭证

```yaml
spring:
  datasource:
    password: ${DB_PASSWORD}     # ✅ 环境变量
    # password: abc123           ❌ 禁止硬编码
```

### 10.3 【推荐】敏感配置加密方案

| 方案 | 适用场景 |
|---|---|
| 环境变量 + K8s Secret | 容器化部署（推荐） |
| HashiCorp Vault | 多团队 / 多环境集中管理 |
| Jasypt（`ENC(xxx)`） | 需要把加密配置入仓时 |
| 云厂商 KMS | 已深度使用云原生 |

### 10.4 【推荐】配置变更审计

生产环境配置变更必须经过 PR 评审 + 运维 review，禁止直接改服务器文件。

## 11. 异步与定时任务规范（v1.2 新增）

### 11.1 【强制】`@Async` 必须配自定义线程池

```java
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("orderAsyncExecutor")
    public ThreadPoolTaskExecutor orderAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("order-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}

// 使用
@Async("orderAsyncExecutor")
public void sendNotification(OrderDO order) { ... }
```

【禁止】用默认 `SimpleAsyncTaskExecutor`（每次新建线程，无限制）。

### 11.2 【强制】`@Scheduled` 多实例必须配 ShedLock

多实例部署时 `@Scheduled` 会在每个节点都执行，必须用 ShedLock 保证集群单次执行：

```java
@Scheduled(cron = "0 0 2 * * ?")            // 每天凌晨 2 点
@SchedulerLock(name = "dailyReportJob",
               lockAtLeastFor = "PT5M",     // 至少持有 5 分钟
               lockAtMostFor = "PT30M")     // 最多 30 分钟
public void generateDailyReport() { ... }
```

### 11.3 【强制】异步异常上报

`@Async` 方法抛出异常默认静默丢失。必须配 `AsyncUncaughtExceptionHandler`：

```java
@Configuration
public class AsyncExceptionHandlerConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncExceptionHandlerConfig.class);

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            log.error("[traceId={}] Async exception in method {}",
                      MDC.get("trace_id"), method.getName(), ex);
            // 上报钉钉 / Sentry
        };
    }
}
```

---
