← [返回 README](./README.md)

# LDX2T Commons AccessToken 用户指南

> **JWT + Redis 双重验证 | 分布式令牌管理 | 企业级访问控制**

## 📚 目录

- [核心特性](#核心特性)
- [快速开始](#快速开始)
  - [环境要求](#环境要求)
  - [三步接入](#三步接入)
- [配置详解](#配置详解)
  - [基础配置](#基础配置)
  - [策略配置](#策略配置)
  - [配置示例](#配置示例)
- [使用指南](#使用指南)
  - [生成 Token](#1-生成-token)
  - [验证 Token](#2-验证-token)
  - [Token 注销](#3-token-注销)
  - [Token 刷新](#4-token-刷新)
  - [获取 Token 信息](#5-获取-token-信息)
- [高级特性](#高级特性)
  - [互斥登录](#互斥登录)
  - [限次消费](#限次消费)
  - [限时激活](#限时激活)
  - [自动续期](#自动续期)
- [架构原理](#架构原理)
  - [Token 结构](#token-结构)
  - [Redis 存储结构](#redis-存储结构)
  - [验证流程](#验证流程)
- [最佳实践](#最佳实践)
- [常见问题](#常见问题)
- [错误码](#错误码)
- [性能监控](#性能监控)

---

## 核心特性

| 特性 | 说明 | 优势 |
|------|------|------|
| **JWT + Redis 双重验证** | JWT 签名校验 + Redis 状态管理 | 高性能 + 强一致性 |
| **灵活策略配置** | 支持多种 Token 类型，独立策略 | 满足不同业务场景 |
| **互斥登录支持** | 基于 key 字段的单点登录控制 | 防止多设备重复登录 |
| **自动续期机制** | 可配置的 Token 自动延期 | 提升用户体验 |
| **限次消费功能** | Token 最大使用次数限制 | 临时凭证、优惠券场景 |
| **限时激活功能** | 生成后指定时间内必须使用 | 邀请码、验证码场景 |
| **线程安全上下文** | ThreadLocal 存储，支持异步传递 | 业务代码便捷访问 |
| **注解式验证** | `@RequiresToken` 声明式鉴权 | 简化开发，减少样板代码 |

---

## 快速开始

### 环境要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 必需 |
| Spring Boot | 3.2+ | 必需，使用 Jakarta 命名空间 |
| Redis | 6+ | 必需，用于 Token 状态存储 |
| Spring Application Name | 必填 | 用于 Redis Key 前缀 |

### 三步接入

**步骤 1：添加依赖**

```xml
<dependency>
    <groupId>com.ldx2t</groupId>
    <artifactId>ldx2t-commons-all</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**步骤 2：配置文件**

```yaml
spring:
  application:
    name: my-app  # 必填，用于生成 Redis Key 前缀

ldx2t:
  commons:
    access-token:
      enabled: true
      secretKey: your-secret-key-change-me  # 必填，JWT 签名密钥
      hashSalt: optional-salt  # 可选，Redis Key 加密盐值
      expireTime: 86400  # 全局默认过期时间（秒）
      redisDatasource: stringRedisTemplate  # 可选，指定 Redis Bean 名称

      policies:
        login:  # 用户登录 Token
          key: [uid]  # 互斥键，支持单个字段或数组
          expireTime: 7200  # 2小时
          autoRenew: true  # 自动续期
          renewIncrement: 1800  # 每次续期30分钟
```

**步骤 3：使用注解**

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

    // 方法级别验证
    @RequiresToken("login")
    @GetMapping("/profile")
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

