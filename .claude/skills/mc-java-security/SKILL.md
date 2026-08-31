---
name: mc-java-security
description: Java Spring Boot 后端安全相关代码激活。覆盖认证（JWT/OAuth2/Session）、授权（RBAC/ABAC）、数据权限（行级/列级）、密码安全、接口签名防重放、文件上传安全、敏感数据加密、审计日志、防常见攻击（SQL 注入/XSS/CSRF/SSRF）。触发词：安全、认证、授权、鉴权、JWT、Token、登录、权限、RBAC、角色、数据权限、行级权限、密码、bcrypt、签名、HMAC、防重放、文件上传、审计、越权、OAuth2、Spring Security、拦截器。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: backend
  tags: [security, authentication, authorization, jwt, rbac, oauth2, spring-security, bcrypt, hmac, audit, encryption]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - Java SpringBoot 后端安全规范 v1.0.md
  related-skills: [mc-web-security, mc-java-spec, mc-api-spec]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "实现用户登录接口（JWT 签发）"
    - "Spring Security 配置 RBAC"
    - "数据权限：用户只能看自己的订单"
    - "接口怎么防重放攻击"
    - "文件上传怎么校验安全"
    - "密码字段用什么加密存储"
    - "微信扫码登录后端怎么对接"
    - "审计日志表怎么设计"
---

# Java SpringBoot 后端安全规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 登录 / Token 签发 | 场景一：JWT 认证 |
| 接口访问权限 | 场景二：RBAC 授权 |
| 数据可见范围 | 场景三：数据权限 |
| 密码存储 / 校验 | 场景四：密码安全 |
| 接口防重放 | 场景五：HMAC 签名 |
| 文件上传 | 场景六：文件上传 |
| 操作审计 | 场景七：审计日志 |
| 微信 / 钉钉 / GitHub 登录 | 场景八：OAuth2 |
| 检查代码合规 | 场景九：P0 必查 6 项 |
| 退出本规范 | 「退出 mc-java-security」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 技术栈 | Spring Security 6 + JJWT + Spring OAuth2 Client |
| **适用** | Java 后端所有安全相关代码 |
| **不适用** | 前端安全（→ mc-web-security）、API 契约（→ mc-api-spec）、DB 表设计（→ mc-database-spec） |
| 核心原则 | **安全红线 > 业务需求**；冲突时安全优先 |
| 退出 | 「退出 mc-java-security」 |

## 2. 全局铁律

1. **密码必须 bcrypt cost ≥ 12** — 禁明文 / MD5 / SHA1
2. **JWT 密钥 ≥ 256 位，环境变量注入**；必含 `exp/iat/iss/sub/jti`
3. **access_token ≤ 2h，refresh_token ≤ 30d**，refresh 一次性
4. **越权检查必须服务端强制** — 禁信任前端传 `user_id`，必须从 SecurityContext 取
5. **数据权限必须服务端拦截** — 行级走拦截器，列级走 DTO 过滤
6. **接口签名**：HMAC-SHA256 + timestamp（±5min）+ nonce（Redis 10min 防重放）
7. **文件上传**：Magic Number 校验 + UUID 重命名 + 独立 OSS 域名（禁带主站 Cookie）
8. **SQL 必须 `#{}` 参数化**，禁 `${}` 拼接
9. **敏感数据加密存储** — 身份证 / 银行卡 AES-256-GCM；密钥走 KMS
10. **关键操作入审计表** — append-only + hash chain 防篡改

## 3. 场景判定

```
当前任务？
├── 登录 / Token 签发 / 刷新           → 场景一：JWT 认证
├── 控制接口访问权限                   → 场景二：RBAC 授权
├── 控制数据可见范围                   → 场景三：数据权限
├── 密码存储 / 校验 / 重置             → 场景四：密码安全
├── 接口防重放 / 签名                  → 场景五：接口签名
├── 文件上传校验                       → 场景六：文件上传
├── 关键操作审计                       → 场景七：审计日志
├── 第三方登录（微信/钉钉/GitHub）     → 场景八：OAuth2
└── 检查代码合规                       → 场景九：P0 必查
```

### 场景一：JWT 认证

**签发**（登录后）：access_token (≤ 2h) + refresh_token (≤ 30d, 一次性)。Claim 含 `sub/iss/aud/exp/iat/jti/type`，自定义 `roles/perms/dept_id`。

**校验**（Filter / Interceptor）：解析 → 校验 `type=access` → 检查黑名单（Redis `access:revoked:{jti}`）→ 注入 SecurityContext。

**刷新**：refresh_token jti 校验 Redis `refresh:valid:{jti}` 是否仍有效；通过后立即作废旧 jti，签发新对。

**撤销**：access_token 进 Redis 黑名单（TTL = 自然过期时间）。

> ❌ 禁：纯 JWT 无撤销 / refresh 永不失效。

**完整代码模板**：详见 `./Java SpringBoot 后端安全规范 v1.0.md` §2。

### 场景二：RBAC 授权（Spring Security）

**SecurityFilterChain**：禁 CSRF（JWT 模式）+ STATELESS Session + URL 级 `authorizeHttpRequests` + 加 `JwtAuthFilter`。

**方法级**：`@PreAuthorize("hasRole('ADMIN') or #userId == authentication.principal.id")`、`@PostAuthorize`（返回后校验所有权）。

**RBAC 三层模型**：用户 ↔ 角色 ↔ 权限点；权限点命名 `<resource>:<action>`（如 `order:delete`）。

**错误处理**：`authenticationEntryPoint` 返回 10200；`accessDeniedHandler` 返回 10300。

**完整代码**：详见主规范 §3。

### 场景三：数据权限（行级 / 列级）

**行级**：MyBatis Plus 拦截器 + `@DataScope(userField = "creator_id", deptField = "dept_id")` 注解；自动追加 WHERE；ADMIN 不限制。

**列级**：DTO 转换时按角色裁剪字段；敏感字段（手机/身份证）按角色决定全显 / 脱敏 / 不返回。

> ⚠️ **禁**信任前端传 `creator_id`/`dept_id`/`user_id`，必须从 SecurityContext 取。

**完整实现**：详见主规范 §4。

### 场景四：密码安全

**强度**：≥ 8 位、大小写 + 数字 + 特殊字符、Top 1000 弱密码黑名单。

**哈希**：`BCryptPasswordEncoder(12)`，禁 MD5/SHA1。

**登录锁定**：5 次失败锁 15min（Redis 计数 `login:fail:{username}`）。

**重置**：一次性 token（Redis TTL 10min），邮件发**重置链接**（非新密码）。

**完整代码**：详见主规范 §5。

### 场景五：接口签名（HMAC 防重放）

适用于开放 API / 第三方对接（非用户态 JWT）。

**签名规则**：`HMAC_SHA256(secret, method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + body_md5)`

**Headers**：`X-Access-Key` / `X-Timestamp`（Unix ms）/ `X-Nonce`（UUID）/ `X-Signature`。

**服务端校验**：①时间戳 ±5min → ②nonce 一次性（Redis SETNX 10min）→ ③取密钥 → ④算签名 → ⑤`MessageDigest.isEqual` 常量时间比较（禁 `equals`）。

**错误码**：10405 缺失 / 10406 过期 / 10407 重复 / 10408 无效。

**完整实现**：详见主规范 §6。

### 场景六：文件上传安全

**多层防御**：网关 413 兜底 → 应用层大小限制 → 后缀白名单 → **Magic Number 探测**（Apache Tika）→ **UUID 重命名**（禁保留原名）→ ClamAV 病毒扫描（重要业务）→ OSS 独立域名（禁带主站 Cookie）→ 静态服务 `Content-Disposition: attachment`。

**禁类型**：exe/html/svg（svg 可含脚本）/ js。

**完整代码**：详见主规范 §7。

### 场景七：审计日志

**表设计**（`audit_log`）：append-only（DB 用户权限 REVOKE UPDATE/DELETE）；字段含 `trace_id/user_id/action/target_type/target_id/before_value(JSONB)/after_value(JSONB)/result/ip/user_agent/created_at`。

**注解切面**：`@Auditable(action="DELETE_ORDER", targetType="order", targetIdSpel="#orderId")`；AOP 自动记录。

**关键操作必须审计**：登录 / 改密 / 角色变更 / 删除 / 资金操作 / 数据导出。

**不可篡改**：每天定时任务生成 hash chain（`sha256(prev_hash || batch)`），定期校验。

**完整实现**：详见主规范 §8、§9。

### 场景八：OAuth2 第三方登录

**流程**：前端跳 `/v1/auth/oauth/{provider}?redirect_uri=xxx` → 后端生成 state（Redis TTL 10min）→ 302 到第三方 → 用户授权 → 回调 `/v1/auth/oauth/{provider}/callback?code=xxx&state=xxx` → 校验 state（一次性）→ 用 code 换 token → 拉用户信息 → 关联本地用户 → 签发本系统 JWT → 302 到前端。

**state 防 CSRF**：必须校验 + 一次性 + 时间窗。

**用户绑定**：第三方 openid 与本地 user_id 多对一。

**完整实现**：详见主规范 §10。

### 场景九：规范检查（P0 必查 6 项）

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | 密码用 bcrypt（cost ≥ 12） | grep `BCryptPasswordEncoder`、`MD5`、`SHA1` |
| 2 | JWT 密钥环境变量注入 | grep `secret` / `signing-key` 配置，禁硬编码 |
| 3 | SQL 用 `#{}` 不用 `${}` | grep Mapper XML |
| 4 | 越权检查从 SecurityContext 取 user_id | grep `@PathVariable.*user_id` 等，核对未直接信任 |
| 5 | 文件上传 Magic Number 校验 | grep `MultipartFile`，检查是否有 Tika 探测 |
| 6 | 敏感字段加密存储 | 查 DDL，身份证 / 银行卡字段是否走加密 TypeHandler |

**P1/P2/P3**：refresh 一次性 / 签名 constant time 比较 / 审计表 append-only / OAuth state 校验 / Spring Security STATELESS / 安全响应头（HSTS / CSP / X-Frame-Options）/ SSRF 校验。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./Java SpringBoot 后端安全规范 v1.0.md` | 详细规则 + 完整代码模板 | v1.0 |
| `../mc-java-spec/Java SpringBoot 后端开发规范 v1.2.md` §7 | 通用安全（Druid / JWT 基础 / SQL 注入） | v1.2 |
| `../mc-api-spec/API 响应结构与错误码规范 v1.6.md` §7 | 错误码：10200-10206 认证 / 10300-10303 权限 | v1.6 |
| `../mc-database-spec/` | 审计日志表 / 字段加密 TypeHandler | v1.2/v1.3 |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| 前端 Token 管理 / 路由守卫 / 按钮权限 | mc-web-security |
| Java 代码层安全实现 | mc-java-spec §4、§7 |
| 认证 / 权限错误码 | mc-api-spec v1.6 §7 |
| 审计日志表 / 字段加密 | mc-database-spec |
| 文件上传错误码（10503-10506） | mc-api-spec v1.7 §7.9 |

**安全责任划分**：后端是真正的安全校验层；前端只是体验优化。**前端所有控制都可被绕过，后端必须独立校验**。
