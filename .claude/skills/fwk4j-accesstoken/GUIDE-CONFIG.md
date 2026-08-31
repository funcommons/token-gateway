← [返回 README](./README.md)

## 配置详解

### 基础配置

| 配置项 | 类型 | 必填 | 默认值 | 说明 |
|--------|------|------|--------|------|
| `enabled` | boolean | 是 | `false` | 是否启用 AccessToken 功能 |
| `secretKey` | String | 是 | - | JWT 签名密钥，建议复杂且定期更换 |
| `hashSalt` | String | 否 | `""` | Redis Key 哈希盐值，增强安全性 |
| `expireTime` | long | 否 | `86400` | 全局默认过期时间（秒） |
| `redisDatasource` | String | 否 | `stringRedisTemplate` | 指定使用的 Redis Bean 名称 |

### 策略配置

每个 Token 类型（Policy）支持以下参数：

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `key` | List<String> | 是 | - | 互斥键字段列表，支持联合主键 |
| `expireTime` | Long | 否 | 继承全局 | 过期时间（秒） |
| `maxUsage` | Integer | 否 | 无限制 | 最大使用次数 |
| `autoRenew` | Boolean | 否 | `false` | 是否自动续期 |
| `renewIncrement` | Long | 否 | - | 续期延长时间（秒） |
| `activationTimeLimit` | Long | 否 | 无限制 | 激活时限（秒） |

### 配置示例

#### 示例 1：用户登录 Token（互斥登录）
```yaml
policies:
  login:
    key: [uid]  # 同一用户只能有一个有效 Token
    expireTime: 7200  # 2小时
    autoRenew: true  # 自动续期
    renewIncrement: 1800  # 每次使用延长30分钟
```

#### 示例 2：管理员 Token（联合主键）
```yaml
policies:
  admin:
    key: [uid, roleId]  # 用户ID + 角色ID联合唯一
    expireTime: 3600  # 1小时
    maxUsage: 100  # 最多使用100次
```

#### 示例 3：API Key（长期有效）
```yaml
policies:
  api:
    key: [appId]  # 应用ID唯一
    expireTime: 31536000  # 1年
```

#### 示例 4：邀请码（限时激活）
```yaml
policies:
  invite:
    key: [inviteCode]  # 邀请码唯一
    expireTime: 604800  # 7天有效期
    activationTimeLimit: 300  # 生成后5分钟内必须激活
    maxUsage: 1  # 仅能使用一次
```

#### 示例 5：多设备登录（允许不同设备）
```yaml
policies:
  multiDevice:
    key: [uid, deviceId]  # 用户ID + 设备ID
    expireTime: 86400  # 24小时
    autoRenew: true
```

---

## 使用指南

### 1. 生成 Token

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

@Service
public class AuthService {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    public String generateUserToken(User user) {
        // 构建业务数据
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", user.getId());
        claims.put("username", user.getUsername());
        claims.put("role", user.getRole());
        claims.put("permissions", user.getPermissions());

        // 生成 Token
        return tokenGenerator.generateToken("login", claims);
    }

    public String generateApiKey(String appId, String appSecret) {
        Map<String, Object> claims = Map.of(
            "appId", appId,
            "permissions", getApiPermissions(appId)
        );

        return tokenGenerator.generateToken("api", claims);
    }

    private List<String> getApiPermissions(String appId) {
        // 获取 API 权限列表的逻辑
        return permissionService.getApiPermissions(appId);
    }
}
```

### 2. 验证 Token

#### 注解式验证（推荐）
```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.annotation.RequiresToken;
import com.ldx2t.commons.accesstoken.context.TokenContext;
import com.ldx2t.commons.api.ApiResponse;  // 来自 ldx2t-commons-api

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

// ===== Controller 1：方法级别验证 =====
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    // 方法级别验证
    @RequiresToken("login")
    @PostMapping
    public ApiResponse<Long> createOrder(@RequestBody CreateOrderDTO dto) {
        // Token 已通过验证，可直接获取用户信息
        Long uid = TokenContext.getClaim("uid", Long.class);
        String role = TokenContext.getClaim("role", String.class);

        // 业务逻辑
        Long orderId = orderService.createOrder(uid, dto);
        return ApiResponse.success(orderId);
    }

    @RequiresToken("login")
    @GetMapping("/{orderId}")
    public ApiResponse<OrderVO> getOrder(@PathVariable Long orderId) {
        Long uid = TokenContext.getClaim("uid", Long.class);
        OrderVO order = orderService.getOrder(orderId, uid);
        return ApiResponse.success(order);
    }
}

// ===== Controller 2：类级别验证 =====
@RestController
@RequestMapping("/api/admin")
@RequiresToken("admin")  // 类级别，所有方法都需要 admin Token
public class AdminController {

    @Autowired
    private UserService userService;

    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers() {
        // 自动验证 admin Token
        return ApiResponse.success(userService.listAll());
    }

    @DeleteMapping("/users/{userId}")
    public ApiResponse<Void> deleteUser(@PathVariable Long userId) {
        userService.deleteUser(userId);
        return ApiResponse.success();
    }
}
```

#### 手动验证
```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import com.ldx2t.commons.accesstoken.util.TokenUtils;
import com.ldx2t.commons.accesstoken.config.AccessTokenProperties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ExternalApiService {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AccessTokenProperties properties;

    public boolean validateToken(String token) {
        try {
            // 1. 解析 JWT，获取 type 和 hash
            Map<String, Object> payload = TokenUtils.parseToken(token, properties.getSecretKey());
            String type = (String) payload.get("type");
            String hash = (String) payload.get("hash");

            // 2. 构建 Redis Key
            String redisKey = tokenGenerator.buildRedisKey(type, hash);

            // 3. 查询 Redis
            String redisValue = redisTemplate.opsForValue().get(redisKey);

            return redisValue != null;
        } catch (Exception e) {
            return false;
        }
    }
}
```

### 3. Token 注销

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.annotation.RequiresToken;
import com.ldx2t.commons.accesstoken.context.TokenContext;
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import com.ldx2t.commons.api.ApiResponse;  // 来自 ldx2t-commons-api

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    @Autowired
    private AuthService authService;

    @PostMapping("/logout")
    @RequiresToken("login")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authHeader) {
        // 提取 Token
        String token = authHeader.substring(7);

        // 注销 Token（从 Redis 删除）
        tokenGenerator.revokeToken(token);

        return ApiResponse.success();
    }

    @PostMapping("/logout/all")
    @RequiresToken("login")
    public ApiResponse<Void> logoutAllDevices() {
        // 获取当前用户信息
        Long uid = TokenContext.getClaim("uid", Long.class);

        // 通过业务逻辑注销该用户的所有 Token
        // （需要自行实现，因为 SDK 只能操作单个 Token）
        authService.logoutAllUserTokens(uid);

        return ApiResponse.success();
    }
}
```

### 4. Token 刷新

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import com.ldx2t.commons.accesstoken.util.TokenUtils;
import com.ldx2t.commons.accesstoken.config.AccessTokenProperties;
import com.ldx2t.commons.api.ApiResponse;  // 来自 ldx2t-commons-api

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

// FastJSON2 解析
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

@RestController
@RequestMapping("/api/token")
public class TokenController {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    @Autowired
    private AccessTokenProperties properties;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @PostMapping("/refresh")
    public ApiResponse<String> refreshToken(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7);

        // 解析当前 Token（仅获取 type 和 hash）
        Map<String, Object> payload = TokenUtils.parseToken(token, properties.getSecretKey());
        String type = (String) payload.get("type");
        String hash = (String) payload.get("hash");

        // 从 Redis 获取完整 Claims
        String redisKey = tokenGenerator.buildRedisKey(type, hash);
        String redisValue = redisTemplate.opsForValue().get(redisKey);

        if (redisValue == null) {
            return ApiResponse.error(10201, "Token 已过期，请重新登录");
        }

        JSONObject redisData = JSON.parseObject(redisValue);
        Map<String, Object> claims = redisData.getObject("claims", Map.class);

        // 生成新 Token（旧 Token 会自动失效）
        String newToken = tokenGenerator.generateToken(type, claims);

        return ApiResponse.success(newToken);
    }
}
```

### 5. 获取 Token 信息

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.context.TokenContext;
import com.ldx2t.commons.accesstoken.context.TokenContext.ContextData;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BusinessService {

    public void processOrder() {
        // 获取当前 Token 类型
        String tokenType = TokenContext.getTokenType();

        // 获取单个 Claim
        Long uid = TokenContext.getClaim("uid", Long.class);
        String username = TokenContext.getClaim("username", String.class);
        List<String> permissions = TokenContext.getClaim("permissions", List.class);

        // 获取所有 Claims
        Map<String, Object> allClaims = TokenContext.getClaims();

        // 业务逻辑
        log.info("用户 {} ({}) 正在处理订单", username, uid);
    }

    // ===== 异步线程中使用 TokenContext =====
    @Async
    public void asyncProcess() {
        // 获取主线程的上下文
        TokenContext.ContextData context = TokenContext.getContext();

        // 在异步线程中设置
        CompletableFuture.runAsync(() -> {
            TokenContext.setContext(context);

            // 现在可以安全使用 TokenContext
            Long uid = TokenContext.getClaim("uid", Long.class);
            // ... 异步业务逻辑

            // 清理上下文
            TokenContext.clear();
        });
    }
}
```

---

