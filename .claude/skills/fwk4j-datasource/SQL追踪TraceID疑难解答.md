# SQL 追踪 TraceID 疑难解答

## 📋 概述

本文档帮助您解决使用 `ldx2t-commons-datasource` 时遇到的 SQL TraceID 追踪问题。

### 常见问题

1. **为什么 SQL 日志中没有 `/*traceid=..*/` 注释?**
2. **为什么 TraceID 显示为 `/*traceid=none,topic=..*/`?**
3. **为什么有的 SQL 有 TraceID,有的没有?**

---

## 🔍 快速诊断流程

```
SQL 日志中无 TraceID 注释?
│
├─ 步骤 1: 检查配置
│   └─ mode 是否为 DISABLED?
│       ├─ 是 → 启用追踪 (设置为 ALL 或 WRITE_ONLY)
│       └─ 否 → 继续下一步
│
├─ 步骤 2: 检查追踪模式
│   └─ 是否使用 WRITE_ONLY 模式 + SELECT 查询?
│       ├─ 是 → 这是预期行为 (WRITE_ONLY 跳过读操作)
│       └─ 否 → 继续下一步
│
├─ 步骤 3: 检查 Filter 注册
│   └─ Druid 是否正确加载 TraceIdDruidFilter?
│       ├─ 否 → 检查 Spring Boot 自动配置
│       └─ 是 → 继续下一步
│
└─ 步骤 4: 检查 MDC 设置
    └─ MDC 中是否设置了 TraceID?
        ├─ 否 → TraceID 会显示为 "none"
        └─ 是 → 检查 MDC 键名是否正确
```

---

## 🛠️ 问题 1: SQL 日志没有 TraceID 注释

### 可能原因

#### 原因 1.1: 追踪模式为 DISABLED

**症状:**
```
// 日志输出
SELECT * FROM users WHERE id = 1
```

**检查配置:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: DISABLED  # ❌ 追踪已禁用
```

**解决方案:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: ALL  # ✅ 启用全量追踪
            topic: my-application
```

---

#### 原因 1.2: WRITE_ONLY 模式下执行了 SELECT 查询

**症状:**
```
// 日志输出
SELECT * FROM users WHERE id = 1  -- 没有 TraceID

INSERT INTO users (name) VALUES ('张三')  -- 有 TraceID
/*traceid=abc123,topic=my-app*/ INSERT INTO users (name) VALUES ('张三')
```

**说明:**

`WRITE_ONLY` 模式仅追踪写操作 (INSERT, UPDATE, DELETE),跳过读操作 (SELECT)。

**检查配置:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: WRITE_ONLY  # ⚠️ 只追踪写操作
```

**解决方案:**

如果需要追踪 SELECT 查询,请改用 `ALL` 模式:

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: ALL  # ✅ 追踪所有 SQL
            topic: my-application
```

---

#### 原因 1.3: Filter 未正确注册

**检查方法:**

启用 DEBUG 日志查看 Filter 注册情况:

```yaml
logging:
  level:
    com.ldx2t.commons.datasource.tracing: DEBUG
```

**预期日志输出:**
```
DEBUG c.l.c.d.t.SqlTracingAutoConfiguration : Registering TraceIdDruidFilter for datasource: default
DEBUG c.l.c.d.t.TraceIdDruidFilter : TraceIdDruidFilter initialized with mode=ALL, topic=my-app
```

**解决方案:**

如果未看到以上日志,请检查:
1. `ldx2t-commons-datasource` 依赖是否正确引入
2. Spring Boot 自动配置是否启用
3. 数据源名称是否正确

---

#### 原因 1.4: SQL 已包含 TraceID 注释 (防重复注入)

**说明:**

Filter 会检查 SQL 是否已包含 `/*traceid=` 开头的注释,防止重复注入。

**示例:**
```java
// 手动添加的注释不会被覆盖
String sql = "/*traceid=manual-id*/ SELECT * FROM users";
// 输出: /*traceid=manual-id*/ SELECT * FROM users
```

---

## 🛠️ 问题 2: TraceID 显示为 "none"

### 原因: MDC 中未设置 TraceID

**症状:**
```
// 日志输出
/*traceid=none,topic=my-app*/ SELECT * FROM users WHERE id = 1
```

**说明:**

`DefaultTraceIdProvider` 从 MDC (Mapped Diagnostic Context) 获取 TraceID,按以下优先级查找:

1. `traceId`
2. `trace_id`
3. `X-B3-TraceId`
4. `X-Request-Id`
5. `requestId`
6. `request_id`

**如果所有键都未找到,TraceID 默认为 "none"。**

---

### 解决方案 1: 手动设置 MDC

**在请求入口处设置 TraceID:**

```java
import org.slf4j.MDC;
import java.util.UUID;

@Component
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 设置 TraceID
            String traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put("traceId", traceId);

            // 执行请求
            filterChain.doFilter(request, response);
        } finally {
            // 清理 MDC
            MDC.remove("traceId");
        }
    }
}
```

**效果:**
```
// 日志输出
/*traceid=abc123def456,topic=my-app*/ SELECT * FROM users WHERE id = 1
```

---

### 解决方案 2: 集成 Spring Cloud Sleuth

**添加依赖:**

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-sleuth</artifactId>
</dependency>
```

**自动生效:**

Spring Cloud Sleuth 会自动在 MDC 中设置 `traceId`,无需手动编码。

**配置示例:**
```yaml
spring:
  sleuth:
    sampler:
      probability: 1.0  # 采样率 100%
```

---

### 解决方案 3: 集成 SkyWalking

**添加 Agent:**

```bash
java -javaagent:/path/to/skywalking-agent.jar \
     -Dskywalking.agent.service_name=my-application \
     -jar your-application.jar
```

SkyWalking Agent 会自动在 MDC 中设置 `X-B3-TraceId`。

---

### 解决方案 4: 自定义 TraceIdProvider

**实现接口:**

```java
import com.ldx2t.commons.datasource.tracing.TraceIdProvider;
import org.springframework.stereotype.Component;

@Component
public class CustomTraceIdProvider implements TraceIdProvider {

    @Override
    public String getTraceId() {
        // 自定义逻辑: 从请求头获取
        HttpServletRequest request =
            ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes())
                .getRequest();

        String traceId = request.getHeader("X-Trace-Id");
        return traceId != null ? traceId : "none";
    }
}
```

---

## 📝 配置示例

### 正确配置

#### 追踪所有 SQL

```yaml
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
            topic: my-application
```

**效果:**
```
/*traceid=abc123,topic=my-application*/ SELECT * FROM users WHERE id = 1
/*traceid=abc123,topic=my-application*/ INSERT INTO users (name) VALUES ('张三')
/*traceid=abc123,topic=my-application*/ UPDATE users SET age = 30 WHERE id = 1
/*traceid=abc123,topic=my-application*/ DELETE FROM users WHERE id = 1
```

---

#### 仅追踪写操作

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: WRITE_ONLY  # 仅追踪写操作
            topic: my-application
```

**效果:**
```
SELECT * FROM users WHERE id = 1  -- ❌ 无 TraceID

/*traceid=abc123,topic=my-application*/ INSERT INTO users (name) VALUES ('张三')  -- ✅ 有 TraceID
/*traceid=abc123,topic=my-application*/ UPDATE users SET age = 30 WHERE id = 1  -- ✅ 有 TraceID
/*traceid=abc123,topic=my-application*/ DELETE FROM users WHERE id = 1  -- ✅ 有 TraceID
```

---

#### 禁用追踪

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: DISABLED  # 禁用追踪
```

**效果:**
```
SELECT * FROM users WHERE id = 1  -- 所有 SQL 都无 TraceID
INSERT INTO users (name) VALUES ('张三')
```

---

### 错误配置

#### 错误 1: 拼写错误

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: ENABLE  # ❌ 错误! 应为 ALL, WRITE_ONLY 或 DISABLED
```

**后果:** 配置无效,追踪可能不工作。

---

#### 错误 2: 缺少 topic

```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: ALL
            # ❌ 缺少 topic 配置
```

**后果:** topic 将为 null 或默认值。

**正确配置:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        default:
          sql-tracing:
            mode: ALL
            topic: my-application  # ✅ 必须设置
```

---

## 🧪 故障排查步骤

### 步骤 1: 启用 DEBUG 日志

```yaml
logging:
  level:
    com.ldx2t.commons.datasource: DEBUG
    com.alibaba.druid.filter: DEBUG
```

### 步骤 2: 检查 Filter 注册日志

**预期输出:**
```
DEBUG c.l.c.d.t.SqlTracingAutoConfiguration : Registering TraceIdDruidFilter for datasource: default
DEBUG c.l.c.d.t.TraceIdDruidFilter : TraceIdDruidFilter initialized with mode=ALL, topic=my-app
```

### 步骤 3: 验证 MDC 中的 TraceID

**编写测试代码:**
```java
import org.slf4j.MDC;

@RestController
public class TestController {

    @GetMapping("/test-traceid")
    public String testTraceId() {
        String traceId = MDC.get("traceId");
        return "Current TraceID: " + traceId;
    }
}
```

**访问:** `GET /test-traceid`

**预期输出:**
```
Current TraceID: abc123def456
```

**如果输出 `null`,说明 MDC 未设置,需要集成分布式追踪系统。**

---

### 步骤 4: 测试不同 SQL 类型

**编写测试代码:**
```java
@Service
public class TestService {

    @Autowired
    private UserMapper userMapper;

    public void testSqlTracing() {
        // 测试 SELECT
        userMapper.selectById(1);

        // 测试 INSERT
        User user = new User();
        user.setName("张三");
        userMapper.insert(user);

        // 测试 UPDATE
        user.setAge(30);
        userMapper.updateById(user);

        // 测试 DELETE
        userMapper.deleteById(user.getId());
    }
}
```

**查看日志,确认哪些 SQL 有 TraceID。**

---

## ❓ 常见问题 FAQ

### Q1: 为什么有的 SQL 有 TraceID,有的没有?

**A:** 最可能的原因是使用了 `WRITE_ONLY` 模式,该模式仅追踪写操作 (INSERT, UPDATE, DELETE),跳过读操作 (SELECT)。

**解决方案:** 改用 `ALL` 模式追踪所有 SQL。

---

### Q2: TraceID 总是显示 "none" 怎么办?

**A:** 说明 MDC 中未设置任何支持的 TraceID 键。

**解决方案:**
1. 集成分布式追踪系统 (Spring Cloud Sleuth, SkyWalking, Zipkin)
2. 手动在 Filter 中设置 `MDC.put("traceId", ...)`
3. 实现自定义 `TraceIdProvider`

---

### Q3: 如何自定义 TraceID 格式?

**A:** 实现 `TraceIdProvider` 接口并注册为 Spring Bean。

**示例:**
```java
@Component
public class CustomTraceIdProvider implements TraceIdProvider {

    @Override
    public String getTraceId() {
        // 自定义格式: 时间戳 + 随机数
        long timestamp = System.currentTimeMillis();
        String random = UUID.randomUUID().toString().substring(0, 8);
        return timestamp + "-" + random;
    }
}
```

---

### Q4: 如何在多数据源场景下使用不同的 topic?

**A:** 为每个数据源单独配置 `sql-tracing`。

**示例:**
```yaml
ldx2t:
  commons:
    datasource:
      datasources:
        primary:
          sql-tracing:
            mode: ALL
            topic: primary-db

        secondary:
          sql-tracing:
            mode: WRITE_ONLY
            topic: secondary-db
```

---

### Q5: TraceID 注释会影响 SQL 性能吗?

**A:** 影响极小,仅在 SQL 字符串前添加注释,不影响查询计划和执行效率。

**性能对比:**
- 无 TraceID: `SELECT * FROM users WHERE id = 1` (10ms)
- 有 TraceID: `/*traceid=abc123,topic=app*/ SELECT * FROM users WHERE id = 1` (10ms)

**注释在数据库解析时会被忽略,不影响执行计划。**

---

### Q6: 如何在日志中同时输出 TraceID 和 SQL?

**A:** 使用 Logback 配置输出 MDC 中的 TraceID。

**logback-spring.xml:**
```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] [TraceID:%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

**效果:**
```
2025-12-03 10:30:45 [http-nio-8080-exec-1] [TraceID:abc123] INFO  c.l.c.d.t.TraceIdDruidFilter - Executing SQL: /*traceid=abc123,topic=my-app*/ SELECT * FROM users WHERE id = 1
```

---

## 📚 参考资料

### MDC 键名优先级

DefaultTraceIdProvider 按以下顺序查找 MDC 键:

| 优先级 | MDC 键名          | 来源                              |
|--------|-------------------|-----------------------------------|
| 1      | `traceId`         | 手动设置 / Spring Cloud Sleuth    |
| 2      | `trace_id`        | 下划线命名风格                    |
| 3      | `X-B3-TraceId`    | Zipkin / SkyWalking               |
| 4      | `X-Request-Id`    | 标准 HTTP 请求 ID                 |
| 5      | `requestId`       | 驼峰命名风格                      |
| 6      | `request_id`      | 下划线命名风格                    |
| 7      | (默认)            | 返回 "none"                       |

---

### 追踪模式对比

| 模式         | SELECT | INSERT | UPDATE | DELETE | DDL  | 说明                       |
|-------------|--------|--------|--------|--------|------|----------------------------|
| `ALL`       | ✅     | ✅     | ✅     | ✅     | ✅   | 追踪所有 SQL               |
| `WRITE_ONLY`| ❌     | ✅     | ✅     | ✅     | ✅   | 仅追踪写操作,跳过读操作    |
| `DISABLED`  | ❌     | ❌     | ❌     | ❌     | ❌   | 禁用追踪                   |

---

### 相关文档

- [多数据源注入器产品文档](./多Datasource数据源注入器产品文档v2.md)
- [ldx2t-commons 分布式 ID SDK 使用指南](../ldx2t-commons-id/ldx2t-commons 分布式 ID SDK 使用指南.md)
- [Spring Cloud Sleuth 官方文档](https://spring.io/projects/spring-cloud-sleuth)

---

## 📧 联系支持

如果以上解决方案无法解决您的问题,请联系技术支持:

- 邮箱: support@ldx2t.com
- 企业微信: LDX2T 技术支持群
- GitHub Issues: https://github.com/ldx2t/ldx2t-commons-sdk/issues

---

**文档版本:** v1.0
**最后更新:** 2025-12-03
**适用版本:** ldx2t-commons-datasource 1.0.0+
