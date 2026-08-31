← [返回 README](./README.md)

# LDX2T Commons AccessToken 快速开始

> **JWT + Redis 双重验证 | 分布式令牌管理 | 企业级访问控制**

## 🚀 快速导航

- [环境要求](#环境要求)
- [三步接入](#三步接入)
- [核心配置](#核心配置)
- [常用示例](#常用示例)
- [错误处理](#错误处理)

---

## 环境要求

| 组件 | 版本要求 |
|------|---------|
| JDK | 17+ |
| Spring Boot | 3.2+ |
| Redis | 6+ |

---

## 三步接入

### 步骤 1：添加依赖

```xml
<dependency>
    <groupId>com.ldx2t</groupId>
    <artifactId>ldx2t-commons-all</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 步骤 2：基础配置

```yaml
spring:
  application:
    name: my-app  # 必填，用于生成 Redis Key 前缀

ldx2t:
  commons:
    access-token:
      enabled: true
      secretKey: your-secret-key-change-me  # 必填，JWT 签名密钥
      expireTime: 86400  # 全局默认过期时间（秒）
      redisDatasource: stringRedisTemplate  # 可选，指定 Redis Bean 名称

      policies:
        login:  # 用户登录 Token
          key: [uid]  # 互斥键
          expireTime: 7200  # 2小时
          autoRenew: true  # 自动续期
          renewIncrement: 1800  # 每次续期30分钟
```

### 步骤 3：使用注解

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.annotation.RequiresToken;
import com.ldx2t.commons.accesstoken.context.TokenContext;
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import com.ldx2t.commons.api.ApiResponse;  // 来自 ldx2t-commons-api

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.HashMap;

// ===== Controller 层 =====
@RestController
@RequestMapping("/api")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping("/profile")
    @RequiresToken("login")  // 需要 login 类型 Token
    public ApiResponse<UserProfile> getProfile() {
        // 从上下文获取 Token 信息
        Long uid = TokenContext.getClaim("uid", Long.class);
        String username = TokenContext.getClaim("username", String.class);

        UserProfile profile = userService.getProfile(uid);
        return ApiResponse.success(profile);
    }
}

// ===== Service 层 =====
@Service
public class AuthService {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    @Autowired
    private UserService userService;

    public String login(String username, String password) {
        // 1. 验证用户名密码
        User user = authenticate(username, password);

        // 2. 构建 Claims
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", user.getId());
        claims.put("username", user.getUsername());
        claims.put("role", user.getRole());

        // 3. 生成 Token
        return tokenGenerator.generateToken("login", claims);
    }

    private User authenticate(String username, String password) {
        // 实际的用户认证逻辑
        return userService.authenticate(username, password);
    }
}
```

---

## 核心配置

### 基础配置参数

| 配置项 | 必填 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | 是 | `false` | 是否启用 AccessToken 功能 |
| `secretKey` | 是 | - | JWT 签名密钥，建议复杂且定期更换 |
| `hashSalt` | 否 | `""` | Redis Key 哈希盐值，增强安全性 |
| `expireTime` | 否 | `86400` | 全局默认过期时间（秒） |
| `redisDatasource` | 否 | `stringRedisTemplate` | 指定使用的 Redis Bean 名称 |

### Token 策略配置

| 参数 | 说明 |
|------|------|
| `key` | 互斥键字段列表，支持联合主键 |
| `expireTime` | 过期时间（秒） |
| `maxUsage` | 最大使用次数（默认无限制） |
| `autoRenew` | 是否自动续期（默认 false） |
| `renewIncrement` | 续期延长时间（秒） |
| `activationTimeLimit` | 激活时限（秒，默认无限制） |

---

## 常用示例

### 1. 生成 Token

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TokenService {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    // 用户登录 Token
    public String generateLoginToken(User user) {
        Map<String, Object> claims = Map.of(
            "uid", user.getId(),
            "username", user.getUsername(),
            "role", user.getRole()
        );
        return tokenGenerator.generateToken("login", claims);
    }

    // API Key
    public String generateApiKey(String appId) {
        Map<String, Object> claims = Map.of(
            "appId", appId,
            "permissions", getPermissions(appId)
        );
        return tokenGenerator.generateToken("api", claims);
    }

    private List<String> getPermissions(String appId) {
        // 获取 API 权限列表的逻辑
        return permissionService.getApiPermissions(appId);
    }
}
```

### 2. Token 验证

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

    @GetMapping("/orders")
    @RequiresToken("login")
    public ApiResponse<List<Order>> getOrders() {
        Long uid = TokenContext.getClaim("uid", Long.class);
        return ApiResponse.success(orderService.getOrders(uid));
    }
}

// ===== Controller 2：类级别验证 =====
@RestController
@RequestMapping("/api/admin")
@RequiresToken("admin")
public class AdminController {

    @Autowired
    private UserService userService;

    @GetMapping("/users")
    public ApiResponse<List<User>> listUsers() {
        // 自动验证 admin Token
        return ApiResponse.success(userService.listAll());
    }
}
```

#### 手动验证

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.core.AccessTokenGenerator;
import com.ldx2t.commons.accesstoken.exception.AuthException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TokenValidationService {

    @Autowired
    private AccessTokenGenerator tokenGenerator;

    public boolean validateToken(String token) {
        try {
            return tokenGenerator.validateToken(token);
        } catch (AuthException e) {
            log.error("Token 验证失败: {}", e.getMessage());
            return false;
        }
    }
}
```

### 3. Token 注销

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.annotation.RequiresToken;
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

    @PostMapping("/logout")
    @RequiresToken("login")
    public ApiResponse<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7);
        tokenGenerator.revokeToken(token);  // 从 Redis 删除
        return ApiResponse.success();
    }
}
```

### 4. 获取 Token 信息

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.context.TokenContext;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class BusinessService {

    public void processBusiness() {
        // 获取当前 Token 类型
        String tokenType = TokenContext.getTokenType();

        // 获取单个 Claim
        Long uid = TokenContext.getClaim("uid", Long.class);
        String username = TokenContext.getClaim("username", String.class);
        List<String> permissions = TokenContext.getClaim("permissions", List.class);

        // 获取所有 Claims
        Map<String, Object> allClaims = TokenContext.getClaims();

        // 业务逻辑
        log.info("用户 {} ({}) 正在处理业务", username, uid);
    }
}
```

---

## 配置场景

### 场景 1：单设备登录

```yaml
policies:
  login:
    key: [uid]  # 同一用户只能有一个有效 Token
    expireTime: 7200
    autoRenew: true
```

### 场景 2：多设备登录

```yaml
policies:
  multiDevice:
    key: [uid, deviceId]  # 用户ID + 设备ID
    expireTime: 86400
```

### 场景 3：一次性使用（邀请码、优惠券）

```yaml
policies:
  coupon:
    key: [couponCode]
    expireTime: 86400
    maxUsage: 1
    activationTimeLimit: 300  # 生成后5分钟内必须使用
```

### 场景 4：API Key（长期有效）

```yaml
policies:
  api:
    key: [appId]
    expireTime: 31536000  # 1年
```

---

## 高级特性

### 1. 自动续期

```yaml
policies:
  mobileApp:
    key: [uid]
    expireTime: 7200
    autoRenew: true  # 启用自动续期
    renewIncrement: 1800  # 每次使用延长30分钟
```

### 2. 互斥登录

```java
// 用户 A 在设备1登录
String token1 = tokenGenerator.generateToken("login", claimsOfA);

// 用户 A 在设备2登录
String token2 = tokenGenerator.generateToken("login", claimsOfA);

// 此时 token1 失效，token2 生效
```

### 3. 异步线程中使用

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.context.TokenContext;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class AsyncService {

    @Async
    public void asyncProcess() {
        // 获取主线程的上下文
        TokenContext.ContextData context = TokenContext.getContext();

        CompletableFuture.runAsync(() -> {
            try {
                TokenContext.setContext(context);
                // 使用 TokenContext
                Long uid = TokenContext.getClaim("uid", Long.class);
                // ... 业务逻辑
            } finally {
                TokenContext.clear();  // 清理上下文
            }
        });
    }
}
```

---

## 错误处理

### 常见错误码

| 错误码 | 说明 | 处理建议 |
|--------|------|---------|
| 10200 | Token 缺失或无效 | 跳转登录页 |
| 10201 | Token 过期或不存在 | 刷新 Token 或重新登录 |
| 10202 | Token 签名错误 | 重新登录 |
| 10205 | 账号异地登录 | 提示用户并重新登录 |
| 10300 | Token 类型不匹配 | 检查配置 |

### 全局异常处理

```java
// ===== 必需的 import 语句 =====
import com.ldx2t.commons.accesstoken.exception.AuthException;
import com.ldx2t.commons.api.ApiResponse;  // 来自 ldx2t-commons-api

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ApiResponse<Void> handleAuthException(AuthException e) {
        int code = e.getCode();
        String message = e.getMessage();

        switch (code) {
            case 10201:
                return ApiResponse.error(code, "登录已过期，请重新登录");
            case 10205:
                return ApiResponse.error(code, "登录状态失效，请重新登录");
            default:
                return ApiResponse.error(code, message);
        }
    }
}
```

---

