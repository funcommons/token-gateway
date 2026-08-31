← [返回 README](./README.md)

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
