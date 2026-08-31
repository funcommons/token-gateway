← [返回 README](./README.md)

## 最佳实践

### 1. 密钥管理

```yaml
# 生产环境配置
ldx2t:
  commons:
    access-token:
      secretKey: ${TOKEN_SECRET}  # 从环境变量读取
      hashSalt: ${TOKEN_SALT:default-salt}  # 支持默认值
```

**安全建议：**
- 使用配置中心管理密钥，支持动态更新
- 密钥长度至少 32 字符，包含大小写字母、数字、特殊字符
- 定期轮换密钥，但要做好兼容处理
- 不同环境使用不同密钥

### 2. 错误处理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ApiResponse<Void> handleAuthException(AuthException e) {
        int code = e.getCode();
        String message = e.getMessage();

        // 根据错误码特殊处理
        switch (code) {
            case 10201: // Token 过期
                return ApiResponse.error(code, "登录已过期，请重新登录");
            case 10202: // Token 无效
            case 10205: // 账号异地登录
                return ApiResponse.error(code, "登录状态失效，请重新登录");
            default:
                return ApiResponse.error(code, message);
        }
    }
}
```

### 3. 权限设计

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.annotation.RequiresToken;
import com.ldx2t.commons.accesstoken.context.TokenContext;
import com.ldx2t.commons.api.ApiResponse;  // 来自 ldx2t-commons-api
import com.ldx2t.commons.api.ApiException;  // 来自 ldx2t-commons-api

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

// ===== Controller 层：细粒度权限控制 =====
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Autowired
    private UserService userService;

    @RequiresToken("login")
    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN') or hasPermission('user:read')")
    public ApiResponse<List<User>> listUsers() {
        return ApiResponse.success(userService.listAll());
    }
}

// ===== Service 层：业务权限检查 =====
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    public void cancelOrder(Long orderId) {
        Long uid = TokenContext.getClaim("uid", Long.class);
        List<String> permissions = TokenContext.getClaim("permissions", List.class);

        Order order = orderMapper.selectById(orderId);

        // 检查是否是订单所有者
        if (!order.getUserId().equals(uid)) {
            // 或检查是否有管理员权限
            if (!permissions.contains("order:manage")) {
                throw new ApiException(10403, "无权操作此订单");
            }
        }

        // 执行取消逻辑
        orderMapper.updateStatus(orderId, CANCELLED);
    }
}
```

### 4. 性能优化

```yaml
# Redis 连接池优化
spring:
  redis:
    lettuce:
      pool:
        max-active: 20  # 根据并发量调整
        max-idle: 10
        min-idle: 5
        time-between-eviction-runs: 30000
```

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import com.ldx2t.commons.accesstoken.util.TokenUtils;
import com.ldx2t.commons.accesstoken.config.AccessTokenProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;

// ===== 批量验证优化 =====
@Service
public class BatchValidationService {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    @Autowired
    private AccessTokenProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 使用 Pipeline 批量验证 Token
     */
    public Map<String, Boolean> batchValidateTokens(List<String> tokens) {
        // 1. 构建 Redis keys
        List<String> redisKeys = tokens.stream()
            .map(token -> {
                try {
                    Map<String, Object> payload = TokenUtils.parseToken(token, properties.getSecretKey());
                    String type = (String) payload.get("type");
                    String hash = (String) payload.get("hash");
                    return tokenGenerator.buildRedisKey(type, hash);
                } catch (Exception e) {
                    return null;  // 无效 Token
                }
            })
            .filter(key -> key != null)
            .collect(Collectors.toList());

        // 2. 使用 pipeline 批量查询
        List<Object> results = redisTemplate.executePipeluted((RedisCallback<Object>) connection -> {
            return redisKeys.stream()
                .map(key -> connection.get(key.getBytes()))
                .collect(Collectors.toList());
        });

        // 3. 处理结果
        Map<String, Boolean> validationResults = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            validationResults.put(tokens.get(i), results.get(i) != null);
        }

        return validationResults;
    }
}
```

### 5. 监控告警

```yaml
# 监控配置
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
```

**关键监控指标：**
- Token 生成速率
- Token 验证失败率
- Redis 连接数
- Token 平均生命周期
- 并发登录用户数

---

## 常见问题

### Q1: Token 验证失败（10202）
**原因：**
- JWT 签名错误（secretKey 不匹配）
- Token 格式错误
- Token 被篡改

**解决方案：**
1. 检查各服务的 `secretKey` 配置是否一致
2. 确认 Token 传输过程中没有被截断或修改
3. 使用 `TokenUtils.parseToken()` 调试验证

```java
// 调试 Token 解析
import com.ldx2t.commons.accesstoken.util.TokenUtils;
try {
    Map<String, Object> payload = TokenUtils.parseToken(token, secretKey);
    log.info("Token 解析成功: {}", payload);
} catch (AuthException e) {
    log.error("Token 解析失败: {}", e.getMessage());
}
```

### Q2: Token 过期（10203）
**原因：**
- 超过 `expireTime` 时间
- 超过 `hardExpireAt` 硬截止时间
- 未及时续期

**解决方案：**
1. 检查过期时间配置是否合理
2. 启用 `autoRenew` 自动续期
3. 前端实现 Token 刷新机制

### Q3: 账号异地登录（10205）
**原因：**
- 同一用户在其他设备登录
- Nonce 不匹配

**解决方案：**
1. 这是正常的安全机制，提示用户重新登录
2. 如需多设备登录，修改 key 配置包含 deviceId

```yaml
# 多设备登录配置
policies:
  multiDevice:
    key: [uid, deviceId]  # 包含设备ID
    expireTime: 86400
```

### Q4: Redis 连接失败
**原因：**
- Redis 服务器不可用
- 连接池配置不当
- 网络问题

**解决方案：**
1. 检查 Redis 服务状态
2. 验证连接配置（host、port、password）
3. 调整连接池参数

```yaml
# Redis 连接检查
spring:
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
    timeout: 5000ms
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
        min-idle: 5
```

### Q5: ThreadLocal 内存泄漏
**原因：**
- 异步线程未清理 TokenContext
- 长时间运行的线程未清理

**解决方案：**

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.context.TokenContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

// ===== 正确的异步处理 =====
@Service
public class AsyncService {

    @Async
    public void asyncMethod() {
        try {
            // 异步业务逻辑
            Long uid = TokenContext.getClaim("uid", Long.class);
            // ...
        } finally {
            // 确保清理
            TokenContext.clear();
        }
    }
}

// ===== 使用拦截器自动清理 =====
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");

        // 设置任务装饰器，自动清理 TokenContext
        executor.setTaskDecorator(new TokenContextCleanupDecorator());

        executor.initialize();
        return executor;
    }

    // TokenContext 清理装饰器
    private static class TokenContextCleanupDecorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            return () -> {
                try {
                    runnable.run();
                } finally {
                    TokenContext.clear();
                }
            };
        }
    }
}
```

---

## 错误码

| 错误码 | 说明 | HTTP 状态 | 处理建议 |
|--------|------|-----------|---------|
| 10200 | Token 缺失或无效 | 200 | 跳转登录页 |
| 10201 | Token 过期或不存在 | 200 | 刷新 Token 或重新登录 |
| 10202 | Token 签名错误 | 200 | 重新登录（可能是密钥不匹配） |
| 10203 | Token 格式错误 | 200 | 检查 Token 传输格式 |
| 10204 | Token 使用次数超限 | 200 | 提示用户重新获取 |
| 10205 | 账号异地登录 | 200 | 提示用户并重新登录 |
| 10300 | Token 类型不匹配 | 200 | 检查 @RequiresToken 配置 |
| 10500 | 系统内部错误 | 200 | 记录日志，联系运维 |

---

## 性能监控

### 关键指标

使用 Micrometer 和 Actuator 监控：

```java
// ===== 必需的 import 语句 =====
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

// ===== 自定义监控指标 =====
@Component
public class TokenMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter tokenGenerateCounter;
    private final Counter tokenValidateCounter;
    private final Timer tokenValidateTimer;

    public TokenMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.tokenGenerateCounter = Counter.builder("accesstoken.generate.total")
            .description("Total number of tokens generated")
            .register(meterRegistry);
        this.tokenValidateCounter = Counter.builder("accesstoken.validate.total")
            .description("Total number of token validations")
            .register(meterRegistry);
        this.tokenValidateTimer = Timer.builder("accesstoken.validate.duration")
            .description("Token validation duration")
            .register(meterRegistry);
    }

    public void recordTokenGenerate(String tokenType) {
        tokenGenerateCounter.increment(Tags.of("type", tokenType));
    }

    public void recordTokenValidation(String result) {
        tokenValidateCounter.increment(Tags.of("result", result));
    }

    public Timer.Sample startValidationTimer() {
        return Timer.start(meterRegistry);
    }
}
```

### Prometheus 监控规则

```yaml
# prometheus.yml 规则示例
groups:
  - name: accesstoken
    rules:
      - alert: TokenValidationFailureRate
        expr: rate(accesstoken_validate_total{result="failure"}[5m]) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Token validation failure rate is high"

      - alert: TokenGenerationSpike
        expr: rate(accesstoken_generate_total[5m]) > 100
        for: 1m
        labels:
          severity: info
        annotations:
          summary: "Token generation rate spike detected"
```

### 日志配置

```xml
<!-- logback-spring.xml -->
<configuration>
    <!-- AccessToken 相关日志 -->
    <logger name="com.ldx2t.commons.accesstoken" level="INFO"/>

    <!-- 开启详细日志（生产环境建议设为 WARN） -->
    <logger name="com.ldx2t.commons.accesstoken.interceptor.TokenInterceptor" level="DEBUG"/>

    <!-- 审计日志 -->
    <appender name="AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/accesstoken-audit.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/accesstoken-audit.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%X{traceId}] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.ldx2t.commons.accesstoken" level="INFO" additivity="false">
        <appender-ref ref="AUDIT"/>
    </logger>
</configuration>
```

**关键日志示例：**
```
2024-01-01 10:00:00 [abc123] INFO  TokenInterceptor - Token验证成功: type=login, uid=1001
2024-01-01 10:01:00 [def456] WARN  TokenInterceptor - Token验证失败: type=login, error=账号已在别处登录
2024-01-01 10:02:00 [ghi789] INFO  AccessTokenGenerator - Token生成成功: type=api, appId=app001
```

---

## 技术支持

- **文档仓库**: `ldx2t-commons-sdk`
- **问题反馈**: GitHub Issues
- **版本要求**: Spring Boot 3.2+, Java 17+

---

**快速导航：**
- [快速开始](#快速开始) | [配置详解](#配置详解) | [使用指南](#使用指南)
- [高级特性](#高级特性) | [架构原理](#架构原理) | [最佳实践](#最佳实践)
