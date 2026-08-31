---
name: mc-web-security
description: Vue 3 前端安全相关代码激活。覆盖 Token 存储与自动刷新、路由守卫、按钮级权限指令、XSS/CSRF 防护、axios 拦截器（认证/签名/401 自动刷新）、敏感数据处理、第三方登录回调、验证码。触发词：前端安全、Token 存储 sessionStorage、路由守卫、按钮权限 v-permission、XSS 转义、CSRF、axios 401、OAuth 回调、state 校验、自动刷新 token、登录态、跨标签页同步。
version: 1.0.0
enabled: true
metadata:
  type: domain-spec
  category: frontend
  tags: [security, authentication, authorization, token-management, route-guard, xss, csrf, oauth2-callback, axios-interceptor, vue3]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - Vue 前端安全规范 v1.0.md
  related-skills: [mc-java-security, mc-webui-spec, mc-api-spec]
  author: architecture-team
  last-reviewed: 2026-06-24
  examples:
    - "Token 该存 localStorage 还是 sessionStorage"
    - "Vue 路由守卫怎么做权限拦截"
    - "按钮级权限怎么实现 v-permission"
    - "axios 收到 401 怎么自动刷新 token"
    - "用户输入展示怎么防 XSS"
    - "退出登录要清空哪些数据"
    - "微信登录回调页面怎么写（防 CSRF）"
    - "跨标签页登录态怎么同步"
---

# Vue 3 前端安全规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| Token 存储 / 自动刷新 | 场景一：Token 管理 |
| 路由守卫 / 菜单权限 | 场景二：路由级权限 |
| 按钮显隐 / 禁用 | 场景三：按钮级权限（v-permission） |
| axios 401 自动刷新 / 重试 | 场景四：axios 拦截器 |
| 防用户输入 XSS | 场景五：XSS 防护 |
| 防 CSRF / Cookie 配置 | 场景六：CSRF 防护 |
| 微信 / 钉钉登录回调 | 场景七：第三方登录回调 |
| 验证码 / 行为验证 | 场景八：人机验证 |
| 检查代码合规 | 场景九：P0 必查 6 项 |
| 退出本规范 | 「退出 mc-web-security」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 技术栈 | Vue 3 + Vue Router 4 + Pinia + axios + vue-i18n |
| **适用** | 前端所有安全相关代码（Token / 路由 / 按钮 / 网络 / 输入防护） |
| **不适用** | 后端安全（→ mc-java-security）、UI 布局（→ mc-webui-spec）、API 契约（→ mc-api-spec） |
| 核心原则 | **前端安全是体验层**，所有真正校验必须在后端 |
| 退出 | 「退出 mc-web-security」 |

## 2. 全局铁律

1. **Token 优先存 sessionStorage**（关闭即清），禁 localStorage 存敏感
2. **路由守卫双层校验**：`beforeEach`（全局鉴权）+ `beforeEnter`（页面专属权限）
3. **按钮权限用 `v-permission` 指令**（DOM 移除，禁仅 CSS 隐藏 / `v-if` 字符串硬编码）
4. **前端不做数据权限判断**：服务端返回什么就显示什么
5. **用户输入禁 `v-html`**，必须 `v-text`/`{{}}`；富文本用 DOMPurify 净化
6. **axios 拦截器自动处理**：请求加 `Authorization`+`X-Trace-Id`+写操作加 `Idempotency-Key`；响应 401 自动刷新，失败跳登录
7. **JWT 模式无 CSRF 风险**；如用 Cookie 鉴权，必须 `SameSite=Lax` + 双提交 Token
8. **写操作自动加签名**：HMAC-SHA256（密钥后端 sessionKey 下发，禁前端硬编码）
9. **退出登录**：清 sessionStorage + Pinia + service worker cache + 调后端撤销接口
10. **第三方登录回调必须校验 state**：sessionStorage 暂存（不放 URL），回调比对，防 CSRF

## 3. 场景判定

```
当前任务？
├── Token 存储 / 自动刷新              → 场景一
├── 路由权限 / 菜单权限               → 场景二
├── 按钮显隐 / 操作权限               → 场景三
├── axios 拦截器 / 401 处理           → 场景四
├── 用户输入 / 富文本展示             → 场景五：XSS 防护
├── Cookie / CSRF / 跨站              → 场景六：CSRF 防护
├── 第三方登录回调                    → 场景七
├── 验证码 / 行为验证                 → 场景八
└── 检查代码合规                      → 场景九：P0 必查
```

### 场景一：Token 管理

**存储**：access/refresh 都存 sessionStorage（关闭即清，XSS 时效短）。❌ 禁 localStorage（永久存储）；❌ 禁任何位置存密码。

**例外**：后端配 httpOnly + Secure + SameSite=Strict Cookie 存 refresh_token 是更安全方案（前端 JS 不可读）。

**自动刷新**：详见场景四（axios 拦截器）。

**跨标签页同步**：`storage` 事件天然跨标签页（同源），但 sessionStorage 变更不触发；用 localStorage 当信号通道（仅放时间戳，不放敏感数据）。

**完整 Pinia store 模板**：详见 `./Vue 前端安全规范 v1.0.md` §2。

### 场景二：路由级权限

**双层校验**：`beforeEach`（登录态 + 角色 + 权限点）+ `beforeEnter`（页面专属）。

**路由 meta 规范**：`requiresAuth`（默认 true）/ `roles`（任一匹配）/ `permissions` / `permission`（语法糖）/ `menuVisible`。

**动态路由加载**：登录后从后端拉取用户可访问的路由，`router.addRoute()` 注入。

**菜单生成**：基于路由 `meta` 自动生成，未授权的路由不进菜单也不可手敲 URL。

**完整代码**：详见主规范 §3。

### 场景三：按钮级权限（v-permission）

**指令实现**：`mounted` 时校验权限，不通过则 `el.parentNode.removeChild(el)`（DOM 移除，禁仅 CSS 隐藏）；`updated` 钩子响应权限变化。

**使用**：`v-permission="'order:delete'"`（单权限）或 `v-permission="['order:approve', 'admin']"`（任一）。

**复合判断**：`usePermission()` composable（has/hasAny/hasAll/hasRole）。

> ⚠️ **DOM 移除 ≠ 安全**：用户改 DOM 仍能触发事件，**真正校验在后端**。前端 v-permission 只是体验优化。

**完整代码**：详见主规范 §5。

### 场景四：axios 拦截器（核心）

**请求拦截**：
- `Authorization: Bearer {access_token}`（自动预判 1min 内过期 → 主动刷新）
- `X-Trace-Id`（透传或新生成 UUID）
- 写操作自动加 `Idempotency-Key`（UUID v4）

**响应拦截**：
- 业务 code 0 → 提取 `X-Trace-Id` → 返回 data
- 业务 code 10201/10202（Token 过期）→ 触发刷新 + 重放原请求
- HTTP 401（兜底）→ 触发刷新
- HTTP 429/503 → 读 `Retry-After`，抛 `RateLimitError`
- HTTP 其他 4xx/5xx → 抛 `InfraError`

**防并发刷新**：多个请求同时 401 时，用共享 `refreshing` Promise 保证只刷新一次。

**失败兜底**：refresh 也失败 → `authStore.cleanup()` + 跳登录页。

**错误类**：`BizError` / `RateLimitError` / `InfraError`，全局 `errorHandler` 分类处理。

**完整代码**：详见主规范 §4。

### 场景五：XSS 防护

| 场景 | 正确 | 错误 |
|---|---|---|
| 文本展示 | `{{ user.name }}` / `v-text` | ❌ `v-html="user.name"` |
| 属性绑定 | `:href="url"`（Vue 自动转义） | ❌ 字符串拼接 |
| 富文本 | `v-html="DOMPurify.sanitize(html)"` | ❌ 直接 `v-html` |
| URL 跳转 | 白名单协议（http/https/mailto/tel） | ❌ `javascript:` 协议 |

**DOMPurify 配置**：白名单 tag/attr、禁 `style/class`（防样式注入）、`a` 标签强制 `target=_blank`+`rel=noopener`。

**CSP 头**（后端配，前端配合）：`default-src 'self'; script-src 'self'; object-src 'none'`。

**完整代码**：详见主规范 §6。

### 场景六：CSRF 防护

| 鉴权方式 | CSRF 风险 | 防护 |
|---|---|---|
| **JWT（Authorization Header）** | ❌ 无（不依赖 Cookie） | `withCredentials: false`，无需额外防护 |
| Cookie 鉴权 | ✅ 高 | `SameSite=Lax` + 双提交 Token |
| Cookie + SameSite=None | ✅ 极高 | 必须双提交 Token + Origin/Referer 校验 |

**双提交 Token**：后端下发 `csrf_token` Cookie（非 httpOnly），前端读 Cookie 加到自定义 Header。

**敏感操作二次校验**：改密输原密码 / 转账 OTP / 删数据输「确认删除」字样。

**完整代码**：详见主规范 §7。

### 场景七：第三方登录回调

**安全要点**：
- **state 防 CSRF**：sessionStorage 暂存（不放 URL），回调比对
- **state 含时间戳**：防长期有效（10min 过期）
- **state 一次性**：用过即删
- **redirect_uri 后端白名单**：禁任意跳转
- **token 不入 URL history**：用 sessionStorage 暂存后立即 `history.replaceState` 清 URL

**流程**：跳转发起（生成 state 入 sessionStorage）→ 第三方授权 → 回调页校验 state → 用 code 换 token → 设置本系统登录态 → 跳 redirect_uri。

**完整代码**（OAuthCallback.vue + composables/useOAuth.ts）：详见主规范 §8。

### 场景八：人机验证

| 场景 | 方案 |
|---|---|
| 登录失败 3 次后 | 图形验证码（滑块/点选） |
| 注册 / 改密 | 短信验证码 + 行为验证 |
| 高敏感（转账） | OTP / 生物识别 |
| 评论 / 发帖 | 频率限制 + 行为验证 |

**推荐第三方**：极验 GeeTest / 阿里盾 / 腾讯防水墙 / Google reCAPTCHA。

> ⚠️ **禁自实现验证码**：OCR 易破解。**完整集成示例**：详见主规范 §9。

### 场景九：规范检查（P0 必查 6 项）

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | Token 存 sessionStorage 不存 localStorage | grep `localStorage.setItem.*token` |
| 2 | 用户输入无 `v-html` 未净化 | grep `v-html`，检查是否包 DOMPurify |
| 3 | axios 拦截器有 401 自动刷新 | 检查 `api/client.ts` |
| 4 | 路由守卫有 requiresAuth + roles | 检查 `router/index.ts` |
| 5 | 写操作自动加 Idempotency-Key | 检查 axios 请求拦截 |
| 6 | OAuth 回调有 state 校验 | 检查 OAuthCallback.vue |

**P1/P2/P3**：按钮用 v-permission 指令 / 跨标签页同步 / CSRF 防护（如用 Cookie）/ CSP 头 / 退出登录清理彻底 / 验证码覆盖 / Sentry 脱敏 / URL 不含敏感字段 / 第三方脚本 SRI。

## 4. 关键文件索引

| 文档 | 用途 | 活跃版本 |
|---|---|---|
| `./Vue 前端安全规范 v1.0.md` | 详细规则 + 完整代码模板 | v1.0 |
| `../mc-webui-spec/SKILL.md` | 前端基础规范（axios 基础封装 / TS 类型 / 多品牌） | v1.1 |
| `../mc-java-security/SKILL.md` | 后端安全（JWT 签发 / RBAC / OAuth2） | v1.0 |
| `../mc-api-spec/API 响应结构与错误码规范 v1.6.md` | 错误码：10200-10206 认证 / 10300-10303 权限 / 10500 限流 | v1.6 |

## 5. 与其他 SKILL 协作

| 涉及 | 同时参考 |
|---|---|
| 后端 JWT 签发 / RBAC / OAuth2 实现 | mc-java-security |
| 前端布局 / 国际化 / axios 基础封装 | mc-webui-spec |
| 认证 / 权限 / 限流错误码 | mc-api-spec v1.6 §7 |
| API 契约（响应信封 / trace_id 双通道） | mc-api-spec v1.6 §4、§5.4 |

**安全责任划分**：

| 层 | 职责 |
|---|---|
| **后端**（mc-java-security） | 真正的安全校验：JWT 签发 / RBAC / 数据权限 / 密码 hash / 签名验证 / 审计 |
| **前端**（mc-web-security） | 体验优化：Token 管理 / 路由守卫 / 按钮显隐 / 输入净化 / 错误处理 |
| **关键原则** | **前端所有安全控制都可被绕过，后端必须独立校验** |
