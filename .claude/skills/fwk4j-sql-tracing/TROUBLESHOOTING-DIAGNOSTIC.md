← [返回 README](./README.md)

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

