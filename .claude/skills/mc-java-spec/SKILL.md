---
name: mc-java-spec
description: Java / Spring Boot 后端代码激活。覆盖 Controller / Service / Mapper / DTO / 异常处理 / 配置 / 依赖管理 / Jackson / ORM / Redis / 日志 / 测试。触发词：Java、Spring Boot、@RestController、@Transactional、@ConfigurationProperties、Lombok、record、Jackson、MyBatis Plus、Druid、Redisson、BizException、ApiResponse、pom.xml、Maven、BOM、ThreadPoolExecutor、SLF4J、MDC、JUnit、Testcontainers、JaCoCo、JWT。
version: 1.3.0
enabled: true
metadata:
  type: domain-spec
  category: backend
  tags: [java, spring-boot, jackson, mybatis-plus, druid, redisson, lombok, junit, testcontainers]
  language: zh-CN
  spec-version: v1.2
  related-specs:
    - Java SpringBoot 后端开发规范 v1.2.md
    - Java SpringBoot 后端开发 SDK 白名单 v1.3.md
  related-skills: [mc-api-spec, mc-database-spec]
  author: architecture-team
  last-reviewed: 2026-06-23
  examples:
    - "写一个用户登录 Controller"            # 自动激活：Controller 实现
    - "@Transactional 不生效怎么回事"       # 自动激活：事务问题
    - "Jackson 字段命名怎么转 snake_case"   # 自动激活：JSON 序列化
    - "全局异常处理怎么写"                  # 自动激活：异常处理
    - "pom.xml 引入 fastjson2 报错"         # 自动激活：依赖管理（违规检测）
    - "Druid 监控页怎么关掉"                # 自动激活：配置管理
    - "trace_id 怎么贯穿全链路"             # 自动激活：日志与追踪
    - "@Async 抛异常没日志"                 # 自动激活：异步任务
---

# Java SpringBoot 后端开发规范

由两份文档组成：**开发规范**（编码/Spring Boot/ORM/Redis/Jackson/日志/安全/测试）+ **SDK 白名单**（依赖/版本/License/BOM）。

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 写 Controller / Service / Mapper | 场景一：业务代码 |
| 引依赖 / 改 pom.xml | 场景二：依赖管理 |
| 写 application.yml / @Configuration | 场景三：配置管理 |
| 处理异常 / 返回响应 | 场景四：异常与响应（含 Jackson 速查） |
| 写 Mapper / 事务 / SQL | 场景五：数据访问 |
| 写日志 / trace_id | 场景六：日志与追踪 |
| 写 @Async / @Scheduled | 场景七：异步与定时 |
| 检查代码合规 | 场景八：P0 必查 5 项 |
| 退出本规范 | 「退出 mc-java-spec」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 技术栈 | Java 17 LTS + Spring Boot 3.2 LTS + MyBatis Plus + Druid + Redisson + **Jackson** + RocketMQ |
| **适用** | Java 后端代码（编码 / 配置 / 依赖 / ORM / Redis / 日志 / 测试） |
| **不适用** | API 设计（→ mc-api-spec）、DB 设计（→ mc-database-spec）、前端（→ mc-webui-spec） |
| JSON 库 | **Jackson**（v1.2 起统一），禁 fastjson2 |
| 退出 | 「退出 mc-java-spec」 |

## 2. 全局铁律

1. **业务异常 HTTP 200** — 异常走 `@RestControllerAdvice` 返回 `ApiResponse`
2. **JSON 统一 Jackson** — 全局 snake_case，禁 fastjson2
3. **Long ID → String** — `serializerByType(Long.class, ToStringSerializer.instance)`
4. **金额 String 元 2 位小数** — `BigDecimal.setScale(2, HALF_UP).toPlainString()`
5. **`@Transactional(rollbackFor = Exception.class)`** — 默认只回滚 RuntimeException 是常见坑
6. **Entity 禁 `@Data`** — 用 `@Getter/@Setter`（防 MyBatis 懒加载栈溢出）
7. **DTO/VO 推荐 record**
8. **线程池禁 `Executors.newXxx()`** — 必须 `ThreadPoolExecutor` 显式构造 + 业务命名
9. **trace_id 全链路一致** — body + `X-Trace-Id` Header + MDC + SQL 注释
10. **写操作支持 `Idempotency-Key`**

## 3. 场景判定

```
当前任务？
├── 写 Controller / Service / Mapper 业务代码  → 场景一
├── 引入依赖 / 改 pom.xml                    → 场景二
├── 配置 Spring Boot（yml / @Configuration）→ 场景三
├── 处理异常 / 返回响应                       → 场景四
├── 写 Mapper / 事务 / 复杂 SQL              → 场景五
├── 写日志 / 排查 / trace_id                  → 场景六
├── 写 @Async / @Scheduled                   → 场景七
└── 检查代码合规                              → 场景八
```

### 场景一：业务代码

**包结构**：`com.company.<module>` → controller / service(+impl) / mapper / entity / dto / vo / bo / enums / config / common / exception。

**类命名**（阿里约规）：Controller→`<Resource>Controller`、Service→`<Resource>Service`+`<Resource>ServiceImpl`、Mapper→`<Resource>Mapper`、Entity→`<Resource>DO`、DTO→`<Resource><Action>Request`/`<Action>Response`、VO→`<Resource><Action>VO`、枚举→`<Resource><Type>`（**禁 `Enum` 后缀**）。

**对象模型**：DTO/VO 用 record；Entity 用 `@Getter/@Setter`（禁 `@Data`）；Config Props 用 `@Getter/@Setter`。

**Boolean 陷阱**：对外字段 `is_paid`，Java 成员变量 `paid`（不加 `is` 前缀，防 Jackson/Lombok 双 `is` 坑），Jackson 全局 snake_case 自动桥接。

**阿里约规核心**：魔法值禁止 / `long` 用 `L` / 控制语句加 `{}` / 行宽 120 / 包装类用 `Objects.equals()` / 集合用 `CollectionUtils.isEmpty()` / 线程池用 `ThreadPoolExecutor` / ThreadLocal 必须 `finally remove()`。

详见 v1.2 §3。

### 场景二：依赖管理

**三步检查**：① 查白名单 §1~§11 → ② License 合规（Apache/MIT/BSD ✅；GPL/AGPL/SSPL ❌）→ ③ 业务 POM **禁写** `<version>`。

**红线**：禁 fastjson2 / 禁 `<repositories>` / 测试依赖必须 `<scope>test</scope>` / 禁止协议。

**BOM 清单**（父 POM 必引）：`spring-boot-dependencies` / `mybatis-plus-bom` / `redisson-bom` / `opentelemetry-bom` / `micrometer-bom`。

**冲突排查**：`mvn dependency:tree -Dincludes=...`；解决优先级：父 POM 统一 > exclusion。

详见 SDK 白名单 v1.3。

### 场景三：配置管理

**`@ConfigurationProperties`** 正确用法：`@Component` + `@ConfigurationProperties` 或 `@EnableConfigurationProperties(XxxProperties.class)`。禁用 `@Value` 散落注入。

**配置分层**：`application.yml` / `application-dev.yml` / `application-test.yml` / `application-pre.yml` / `application-prod.yml`。

**敏感项禁止入仓**：DB/Redis/MQ 密码、JWT 密钥、第三方 AK/SK、Druid 监控页凭证 → 必须环境变量 `${...}` 注入。

**Druid 完整配置**（v1.2 §4.4.4）：连接池参数（initial-size: 5, max-active: 20, max-wait: 5000）+ 监控页（环境变量凭证 + IP 白名单 + 禁公网）+ 慢 SQL（dev 200ms / prod 2000ms 按 Profile）+ remove-abandoned 检测泄漏。

### 场景四：异常与响应（含 Jackson 速查）

**`ApiResponse<T>` 信封**（record，6 字段，`@JsonProperty("trace_id")` 显式锁定）：

```java
public record ApiResponse<T>(
    int code, String message, T data,
    List<ApiError> error,
    @JsonProperty("trace_id") String traceId,
    long timestamp
) {
    public static <T> ApiResponse<T> success(T data, String traceId) { ... }
    public static <T> ApiResponse<T> fail(ErrorCode ec, String msg, List<ApiError> errors, String traceId) { ... }
}
```

**`BizException`** 接收 **`ErrorCode` enum**（不是 int）：

```java
public enum ErrorCode {
    NOT_FOUND(10400, "请求资源不存在"),
    SYSTEM_BUSY(10001, "系统繁忙，请稍后再试");
    // 完整见 v1.2 §5.5（v1.6 §7 的 Java 实现参考）
}

public class BizException extends RuntimeException {
    public BizException(ErrorCode errorCode) { ... }
    public BizException(ErrorCode errorCode, String customMessage) { ... }
}
```

**全局异常处理器**（`@RestControllerAdvice`）：业务异常 → `e.getCode()`；参数校验失败 → 10100 + error 数组含子类型；请求体解析失败 → 10103；兜底 → 10001。所有异常返回 HTTP 200 + `X-Trace-Id` Header。

**Jackson 全局配置速查**（一行解决 snake_case + Long→String）：

```java
@Bean
public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    return builder -> {
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        builder.serializerByType(Long.class, ToStringSerializer.instance);
        builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
        builder.modulesToInstall(new JavaTimeModule());
        builder.failOnUnknownProperties(false);
    };
}
```

**金额转换**：`MoneyUtils.format(BigDecimal)` → `setScale(2, HALF_UP).toPlainString()`。

详见 v1.2 §4.2、§4.6、§5。

### 场景五：数据访问

**必开配置**：`mybatis-plus.configuration.map-underscore-to-camel-case: true` + 逻辑删除配置。

**Entity**：`@TableName` + `@TableId(type = IdType.ASSIGN_ID)` + `@TableField(typeHandler = JacksonTypeHandler.class)`（JSONB 字段）。

**事务**：`@Transactional(rollbackFor = Exception.class)`；只读用 `readOnly = true`（注意 MySQL InnoDB 几乎无性能收益）。

**禁 N+1**：循环内查 DB 必须批量。**批量上限**：Mapper foreach/insertBatch ≤ 1000；同步业务批量 ≤ 100（>100 必须异步 Job）。

**SQL 注入**：XML 必须 `#{}`，禁 `${}`；排序字段动态化用白名单 `<choose>`。

详见 v1.2 §4.4、§7.2。

### 场景六：日志与追踪

**SLF4J 占位符** `log.info("id={}", id)`，禁字符串拼接。异常日志带堆栈 `log.error("...", e)`。

**敏感脱敏**：手机 `138****1234` / 身份证 `110101********1234` / 银行卡 `6228******5678` / 邮箱 `a***@example.com` / 密码 token `***`。

**TraceContext 三件套**（v1.2 §6.6 完整实现）：
1. `TraceContext` 类（基于 MDC）
2. `TraceFilter`（请求入口注入 + 响应头写 `X-Trace-Id`）
3. logback pattern 含 `[%X{trace_id}]`
4. MyBatis Interceptor 写 SQL 注释 `/*traceid=xxx*/`

**结构化日志**：生产用 `LogstashEncoder`（基于 Jackson）输出 JSON。

### 场景七：异步与定时

**`@Async`** 必须配自定义线程池：`ThreadPoolTaskExecutor` + 业务命名 `setThreadNamePrefix("order-async-")` + `CallerRunsPolicy` 拒绝策略 + `setWaitForTasksToCompleteOnShutdown(true)`。禁默认 `SimpleAsyncTaskExecutor`。

**`@Scheduled`** 多实例必须 ShedLock 防 repeated execution。

**异步异常**必须配 `AsyncUncaughtExceptionHandler`，否则静默丢失。

详见 v1.2 §11。

### 场景八：规范检查（P0 必查 5 项）

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | **无 fastjson2** | grep pom.xml |
| 2 | Entity 禁用 `@Data` | grep 实体类 |
| 3 | 异常返回 HTTP 200 | 抓真实响应 |
| 4 | 业务 POM 无 `<version>` | grep `pom.xml` |
| 5 | 敏感项环境变量注入 | grep application.yml 中硬编码密码/密钥 |

**P1/P2/P3**：`@Transactional(rollbackFor)` / trace_id 双通道 / Idempotency-Key 支持 / 魔法值 / 线程池自定义 / 命名规范 / 覆盖度 ≥ 80% / Testcontainers / Jackson 全局配置 / `map-underscore-to-camel-case` 等。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./Java SpringBoot 后端开发规范 v1.2.md` | 编码/Spring Boot/ORM/Redis/Jackson/日志/安全/测试/配置/异步 | v1.2 |
| `./Java SpringBoot 后端开发 SDK 白名单 v1.3.md` | 依赖清单/版本/License/BOM/违规检测 | v1.3 |
| `./Java SpringBoot 后端开发规范v1.0.md` / `v1.1.md` | 历史版本（已废弃，仅对照） | - |
| `./Java SpringBoot 后端开发 SDK 白名单 v1.1.md` / `v1.2.md` | 历史版本（已废弃） | - |

## 5. 与其他规范协作

| 涉及 | 同时参考 |
|---|---|
| API URL 设计、响应信封、错误码、分页、Header | `../mc-api-spec/SKILL.md`（v1.6） |
| 数据库表设计、SQL | `../mc-database-spec/SKILL.md` |
| 前端调用、axios 拦截器、TS 类型 | `../mc-webui-spec/SKILL.md`（场景三） |

**字段映射链**：DB `snake_case` ↔ Java 实体 `lowerCamelCase`（MyBatis Plus `map-underscore-to-camel-case`）↔ API 响应 `snake_case`（Jackson `SNAKE_CASE`）。
