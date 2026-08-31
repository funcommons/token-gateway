# Vue 3 前端安全规范 v1.0

> 版本：v1.0
> 修订日期：2026-06-24
> 配套：`./SKILL.md`（精简入口，自动激活）；本文件为详细规则文档
> 关联：mc-webui-spec（前端基础规范）、mc-java-security（后端安全）、mc-api-spec v1.6（错误码 / Header）

---

## 1. 概述

### 1.1 适用范围

Vue 3 + Vue Router 4 + Pinia + axios 前端的所有安全相关代码：

- Token 管理（存储 / 自动刷新 / 撤销）
- 路由级权限（守卫 / 菜单生成）
- 按钮级权限（v-permission）
- 网络层（axios 拦截器 / 签名 / 防重放）
- 输入防护（XSS / CSRF）
- 第三方登录回调
- 人机验证（验证码）
- 跨标签页登录态同步

### 1.2 核心原则

| 原则 | 说明 |
|---|---|
| **前端安全是体验层** | 真正的安全校验必须在后端 |
| **零信任前端** | 用户输入、URL 参数、第三方回调都不可信 |
| **Token 时效最短** | access_token ≤ 2h，refresh_token ≤ 30d |
| **退出彻底** | 清 sessionStorage + Pinia + service worker + 调后端撤销 |
| **可见 ≠ 可用** | 隐藏按钮不等于禁用，最终校验在后端 |

---

## 2. Token 管理

### 2.1 存储策略

| Token 类型 | 存储位置 | 理由 |
|---|---|---|
| access_token | sessionStorage | 关闭标签即清，XSS 偷到时效短（≤ 2h） |
| refresh_token | sessionStorage | 同上 |
| ❌ localStorage | 禁用 | 永久存储，XSS 偷到后可长期冒用 |
| ❌ 任何位置存密码 | 禁用 | 登录后立即丢弃 |
| ❌ 全局变量 / window | 禁用 | 易被开发者工具查看 |

**例外**：如后端配合使用 httpOnly Cookie 存储 refresh_token（前端 JS 不可读，XSS 也偷不到），是更安全的方案。需要后端在 Set-Cookie 时配 `HttpOnly; Secure; SameSite=Strict`。

### 2.2 Pinia Auth Store

```ts
// stores/auth.ts
import { defineStore } from 'pinia'
import { jwtDecode } from 'jwt-decode'

interface AuthState {
  accessToken: string | null
  user: User | null
  roles: string[]
  permissions: string[]
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    accessToken: sessionStorage.getItem('access_token'),
    user: null,
    roles: [],
    permissions: []
  }),

  getters: {
    isLoggedIn: (s) => !!s.accessToken && !isExpired(s.accessToken),
    hasRole: (s) => (role: string) => s.roles.includes(role),
    hasPermission: (s) => (perm: string) => s.permissions.includes(perm)
  },

  actions: {
    async login(credentials: LoginRequest) {
      const res = await authApi.login(credentials)
      this.setTokens(res.accessToken, res.refreshToken)
      this.user = res.user
      this.roles = res.roles
      this.permissions = res.permissions
      this.broadcastChange()
    },

    setTokens(access: string, refresh?: string) {
      this.accessToken = access
      sessionStorage.setItem('access_token', access)
      if (refresh) sessionStorage.setItem('refresh_token', refresh)
    },

    async refresh() {
      const refresh = sessionStorage.getItem('refresh_token')
      if (!refresh) throw new Error('no refresh token')
      const res = await authApi.refresh(refresh)
      this.setTokens(res.accessToken, res.refreshToken)
    },

    async logout() {
      try {
        await authApi.logout()  // 调后端撤销
      } catch {}
      this.cleanup()
    },

    cleanup() {
      this.accessToken = null
      this.user = null
      this.roles = []
      this.permissions = []
      sessionStorage.removeItem('access_token')
      sessionStorage.removeItem('refresh_token')
      sessionStorage.removeItem('trace_id')
      this.broadcastChange()
    },

    broadcastChange() {
      // 跨标签页通知
      localStorage.setItem('auth_changed', Date.now().toString())
      localStorage.removeItem('auth_changed')
    }
  }
})

function isExpired(token: string): boolean {
  try {
    const { exp } = jwtDecode(token)
    return Date.now() >= exp * 1000
  } catch {
    return true
  }
}
```

### 2.3 自动刷新（详见 §4 axios 拦截器）

### 2.4 跨标签页同步

```ts
// main.ts
window.addEventListener('storage', (e) => {
  if (e.key === 'auth_changed') {
    const auth = useAuthStore()
    // 重新读取 sessionStorage
    auth.accessToken = sessionStorage.getItem('access_token')
    if (!auth.accessToken) {
      // 其他标签页退出登录
      router.push({ name: 'login' })
    }
  }
})
```

> 注：`storage` 事件天然跨标签页（同源），但 sessionStorage 变更不触发；用 localStorage 当信号通道（仅放时间戳，不放敏感数据）。

### 2.5 Token 过期前端预判

```ts
// 在请求拦截器中预判，避免无谓 401
client.interceptors.request.use(async cfg => {
  const auth = useAuthStore()
  if (auth.accessToken && willExpireSoon(auth.accessToken)) {
    try { await auth.refresh() } catch { /* ignore, 401 兜底 */ }
  }
  return cfg
})

function willExpireSoon(token: string): boolean {
  try {
    const { exp } = jwtDecode(token)
    return (exp * 1000 - Date.now()) < 60 * 1000  // 1 分钟内过期
  } catch { return false }
}
```

---

## 3. 路由级权限

### 3.1 全局守卫（beforeEach）

```ts
// router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),  // 禁 createWebHashHistory
  routes: [
    { path: '/login', name: 'login', component: Login, meta: { requiresAuth: false } },
    { path: '/', component: Layout, meta: { requiresAuth: true }, children: [
      { path: '', name: 'home', component: Home },
      { path: 'orders', name: 'orders', component: OrderList, meta: { permission: 'order:read' } },
      { path: 'admin/users', name: 'admin-users', component: UserAdmin,
        meta: { roles: ['ADMIN'], permissions: ['user:read'] } }
    ]},
    { path: '/forbidden', name: 'forbidden', component: Forbidden }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const requiresAuth = to.meta.requiresAuth !== false

  // 1. 未登录但需要登录
  if (requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 2. 已登录但访问登录页 → 跳首页
  if (to.name === 'login' && auth.isLoggedIn) {
    return { name: 'home' }
  }

  // 3. 角色校验
  const requiredRoles = to.meta.roles as string[] | undefined
  if (requiredRoles && !requiredRoles.some(r => auth.roles.includes(r))) {
    return { name: 'forbidden' }
  }

  // 4. 权限点校验
  const requiredPerms = (to.meta.permissions as string[]) ||
                       (to.meta.permission ? [to.meta.permission] : [])
  if (requiredPerms.length && !requiredPerms.some(p => auth.permissions.includes(p))) {
    return { name: 'forbidden' }
  }

  return true
})

export default router
```

### 3.2 路由元信息（meta）规范

| 字段 | 类型 | 含义 |
|---|---|---|
| `requiresAuth` | boolean | 是否需要登录（默认 true） |
| `roles` | string[] | 允许的角色（任一匹配即可） |
| `permissions` | string[] | 允许的权限点（任一匹配即可） |
| `permission` | string | 单个权限点（语法糖） |
| `title` | string | 页面标题（i18n key） |
| `menuVisible` | boolean | 是否在菜单显示 |

### 3.3 动态路由加载

```ts
// 登录后从后端拉取用户可访问的路由
async function loadDynamicRoutes() {
  const auth = useAuthStore()
  const routes = await api.getMyRoutes()  // 后端返回路由配置
  routes.forEach(r => router.addRoute('layout', r))
}

// beforeEach 中首次触发
let routesLoaded = false
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (auth.isLoggedIn && !routesLoaded) {
    await loadDynamicRoutes()
    routesLoaded = true
    return { ...to, replace: true }  // 重新进入触发新路由
  }
})
```

### 3.4 菜单生成

```ts
// 从路由 meta 自动生成菜单
const menus = computed(() => {
  const auth = useAuthStore()
  return router.getRoutes()
    .filter(r => r.meta?.menuVisible !== false)
    .filter(r => !r.meta?.roles || r.meta.roles.some(role => auth.roles.includes(role)))
    .filter(r => !r.meta?.permissions ||
                 (r.meta.permissions as string[]).some(p => auth.permissions.includes(p)))
    .map(r => ({ path: r.path, title: t(r.meta.title as string) }))
})
```

---

## 4. axios 拦截器（核心）

### 4.1 完整封装

```ts
// api/client.ts
import axios, { AxiosError, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'
import { v4 as uuidv4 } from 'uuid'
import { useAuthStore } from '@/stores/auth'
import { useTraceStore } from '@/stores/trace'
import router from '@/router'

const client = axios.create({
  baseURL: '/api',
  timeout: 10000,
  withCredentials: false  // JWT 模式禁用 Cookie
})

let refreshing: Promise<void> | null = null

// ============ 请求拦截 ============
client.interceptors.request.use(async (cfg) => {
  const auth = useAuthStore()

  // 1. Authorization
  if (auth.accessToken) {
    // 预判过期
    if (willExpireSoon(auth.accessToken)) {
      try { await refreshOnce() } catch { /* 401 兜底 */ }
    }
    cfg.headers.Authorization = `Bearer ${auth.accessToken}`
  }

  // 2. X-Trace-Id
  const trace = useTraceStore()
  cfg.headers['X-Trace-Id'] = trace.current || trace.ensure()

  // 3. Idempotency-Key（写操作）
  if (['post', 'put', 'patch', 'delete'].includes(cfg.method || '')) {
    cfg.headers['Idempotency-Key'] = uuidv4()
  }

  return cfg
})

// ============ 响应拦截 ============
client.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body.code === 0) {
      // 提取 trace_id
      const traceId = res.headers['x-trace-id'] || body.trace_id
      if (traceId) useTraceStore().set(traceId)
      return body.data
    }

    // Token 过期 → 自动刷新
    if (body.code === 10201 || body.code === 10202) {
      return refreshOnce().then(() => client(res.config))
    }

    return Promise.reject(new BizError(body.code, body.message, body.error))
  },
  async (err: AxiosError) => {
    const status = err.response?.status

    // HTTP 401 兜底
    if (status === 401) {
      try {
        await refreshOnce()
        return client(err.config as InternalAxiosRequestConfig)
      } catch {
        useAuthStore().cleanup()
        router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
        return Promise.reject(err)
      }
    }

    // 限流
    if (status === 429 || status === 503) {
      const retryAfter = Number(err.response?.headers['retry-after']) || 30
      return Promise.reject(new RateLimitError(retryAfter))
    }

    return Promise.reject(new InfraError('网络异常', err))
  }
)

async function refreshOnce() {
  if (!refreshing) {
    refreshing = useAuthStore().refresh().finally(() => { refreshing = null })
  }
  return refreshing
}

function willExpireSoon(token: string): boolean {
  try {
    const { exp } = jwtDecode(token)
    return (exp * 1000 - Date.now()) < 60 * 1000
  } catch { return false }
}

// ============ 错误类 ============
export class BizError extends Error {
  constructor(public code: number, message: string, public errors?: any[]) { super(message) }
}
export class RateLimitError extends Error {
  constructor(public retryAfterSeconds: number) { super('请求过于频繁') }
}
export class InfraError extends Error {
  constructor(msg: string, public cause?: unknown) { super(msg) }
}

export default client
```

### 4.2 错误处理策略

| HTTP | 业务 code | 处理 |
|---|---|---|
| 200 | 0 | 返回 data |
| 200 | 10201/10202 | 自动刷新 + 重放原请求 |
| 200 | 10200/10203/10204 | 跳登录页 |
| 200 | 10300/10301 | 提示无权限，可选跳转 forbidden |
| 200 | 10500/10502 | 读 Retry-After，指数退避（可选） |
| 200 | 10100 | 渲染 error[] 到表单 |
| 200 | 10700 | 显示部分失败列表 |
| 401 | - | 走刷新流程 |
| 403 | - | 跳 forbidden 页 |
| 404 | - | 跳 404 页 |
| 429/503 | - | 读 Retry-After |
| 其他 4xx/5xx | - | 全局错误兜底 |

### 4.3 全局错误兜底

```ts
// main.ts
import { BizError, RateLimitError, InfraError } from '@/api/client'

app.config.errorHandler = (err) => {
  if (err instanceof BizError) {
    ElMessage.error(err.message)
  } else if (err instanceof RateLimitError) {
    ElMessage.warning(`请求过于频繁，${err.retryAfterSeconds} 秒后重试`)
  } else if (err instanceof InfraError) {
    ElMessage.error('网络异常，请稍后重试')
    Sentry.captureException(err)
  } else {
    console.error(err)
    Sentry.captureException(err)
  }
}
```

---

## 5. 按钮级权限（v-permission）

### 5.1 指令实现

```ts
// directives/permission.ts
import type { Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

type PermissionValue = string | string[]

function checkPermission(value: PermissionValue): boolean {
  const auth = useAuthStore()
  if (Array.isArray(value)) {
    return value.some(p => auth.permissions.includes(p))
  }
  return auth.permissions.includes(value)
}

export const vPermission: Directive<HTMLElement, PermissionValue> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<PermissionValue>) {
    if (!checkPermission(binding.value)) {
      el.parentNode?.removeChild(el)  // DOM 移除
    }
  },
  // 响应式更新（权限变化时）
  updated(el: HTMLElement, binding: DirectiveBinding<PermissionValue>) {
    const has = checkPermission(binding.value)
    const hasEl = el.parentNode?.contains(el)
    if (!has && hasEl) el.parentNode?.removeChild(el)
  }
}

// main.ts
app.directive('permission', vPermission)
```

### 5.2 使用

```vue
<el-button v-permission="'order:delete'" type="danger">删除</el-button>
<el-button v-permission="['order:approve', 'admin']">审批</el-button>
<el-button v-permission="'user:reset-password'">重置密码</el-button>
```

### 5.3 复合判断

```ts
// composables/usePermission.ts
export function usePermission() {
  const auth = useAuthStore()
  return {
    has: (p: string) => auth.permissions.includes(p),
    hasAny: (ps: string[]) => ps.some(p => auth.permissions.includes(p)),
    hasAll: (ps: string[]) => ps.every(p => auth.permissions.includes(p)),
    hasRole: (r: string) => auth.roles.includes(r)
  }
}

// 组件内
const { has, hasRole } = usePermission()
const canEdit = computed(() => has('order:edit') || hasRole('ADMIN'))
```

### 5.4 重要提醒

> ⚠️ **DOM 移除 ≠ 安全**
>
> 用户改 DOM 仍能触发事件。**真正的校验必须在后端**。前端 v-permission 只是体验优化（避免点错按钮报错）。

---

## 6. XSS 防护

### 6.1 Vue 默认防护

Vue 3 默认对 `{{ }}` 和 `v-bind` 做转义，安全。

| 场景 | 正确 | 错误 |
|---|---|---|
| 文本展示 | `{{ user.name }}` / `v-text` | ❌ `v-html="user.name"` |
| 属性绑定 | `:href="url"` | ❌ 字符串拼接 |
| 富文本 | `v-html="sanitized"` | ❌ `v-html="raw"` |

### 6.2 富文本净化（DOMPurify）

```ts
import DOMPurify from 'dompurify'

const clean = DOMPurify.sanitize(userInput, {
  ALLOWED_TAGS: ['p', 'br', 'strong', 'em', 'ul', 'ol', 'li', 'a', 'img'],
  ALLOWED_ATTR: ['href', 'target', 'src', 'alt'],
  ALLOW_DATA_ATTR: false,
  FORBID_ATTR: ['style', 'class'],  // 禁样式注入
  FORBID_TAGS: ['form', 'input', 'script', 'iframe']
})

// 配置 a 标签强制 target=_blank + rel=noopener
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})
```

### 6.3 URL 跳转防护

```ts
const SAFE_PROTOCOLS = ['http:', 'https:', 'mailto:', 'tel:']

function safeUrl(url: string): string {
  try {
    const u = new URL(url)
    if (!SAFE_PROTOCOLS.includes(u.protocol)) return '#'  // 拒绝 javascript: data:
    return url
  } catch {
    return '#'
  }
}

// <a :href="safeUrl(userInput)">
```

### 6.4 CSP（Content Security Policy）

后端响应头配置，前端配合：

```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'unsafe-inline';
  style-src 'self' 'unsafe-inline';
  img-src 'self' data: https:;
  connect-src 'self' https://api.example.com;
  font-src 'self' data:;
```

---

## 7. CSRF 防护

### 7.1 JWT 模式（推荐）

JWT 通过 `Authorization` Header 携带，**不依赖 Cookie**，天然无 CSRF 风险。

| 配置 | 值 |
|---|---|
| `axios.withCredentials` | `false` |
| 后端 Cookie | 不下发会话 Cookie |

### 7.2 Cookie 鉴权（如必须用）

```ts
// 1. 后端下发 csrf_token Cookie（非 httpOnly）
// 2. 前端读取并加到自定义 Header
client.interceptors.request.use(cfg => {
  const csrf = getCookie('csrf_token')
  if (csrf) cfg.headers['X-CSRF-Token'] = csrf
  return cfg
})

function getCookie(name: string): string | null {
  const m = document.cookie.match(new RegExp('(^| )' + name + '=([^;]+)'))
  return m ? decodeURIComponent(m[2]) : null
}
```

**Cookie 属性要求**（后端配）：

```
Set-Cookie: session=xxx; HttpOnly; Secure; SameSite=Lax
```

### 7.3 敏感操作二次校验

| 操作 | 二次校验 |
|---|---|
| 改密码 | 输入原密码 |
| 转账 | OTP / 短信验证码 |
| 删除重要数据 | 输入「确认删除」字样 |
| 修改权限 | 多人审批 |

---

## 8. 第三方登录回调

### 8.1 安全要点

| 防护 | 实现 |
|---|---|
| **state 防 CSRF** | sessionStorage 暂存（不放 URL），回调时比对 |
| **state 含时间戳** | 防长期有效（10min 过期） |
| **state 一次性** | 用过即删 |
| **redirect_uri 白名单** | 后端校验，禁任意跳转 |
| **token 不入 URL history** | 用 sessionStorage 暂存后立即清 URL |

### 8.2 跳转发起

```ts
// composables/useOAuth.ts
import { v4 as uuidv4 } from 'uuid'

export function useOAuth() {
  const router = useRouter()

  function redirectTo(provider: 'wechat' | 'dingtalk' | 'github') {
    const state = `${uuidv4()}:${Date.now()}`
    sessionStorage.setItem('oauth_state', state)
    sessionStorage.setItem('oauth_redirect', router.currentRoute.value.fullPath)
    window.location.href = `/api/v1/auth/oauth/${provider}?state=${state}`
  }

  return { redirectTo }
}
```

### 8.3 回调处理

```vue
<!-- views/auth/OAuthCallback.vue -->
<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

onMounted(async () => {
  const { code, state } = route.query

  // 1. state 校验（防 CSRF）
  const expectedState = sessionStorage.getItem('oauth_state')
  sessionStorage.removeItem('oauth_state')

  if (!state || state !== expectedState) {
    ElMessage.error('登录失败：state 校验不通过')
    return router.replace({ name: 'login' })
  }

  // 2. 时间戳校验（防长期有效）
  const [, timestamp] = (state as string).split(':')
  if (Date.now() - Number(timestamp) > 10 * 60 * 1000) {
    ElMessage.error('登录超时，请重试')
    return router.replace({ name: 'login' })
  }

  // 3. 用 code 换 token（调后端）
  try {
    await auth.exchangeOAuthCode(code as string, expectedState)
    const redirect = sessionStorage.getItem('oauth_redirect') || '/'
    sessionStorage.removeItem('oauth_redirect')
    router.replace(redirect)
  } catch (e) {
    ElMessage.error('登录失败，请重试')
    router.replace({ name: 'login' })
  }

  // 4. 清理 URL（防 token 入 history）
  window.history.replaceState({}, '', '/')
})
</script>

<template>
  <div class="oauth-callback">
    <el-icon class="loading"><Loading /></el-icon>
    <p>正在登录...</p>
  </div>
</template>
```

### 8.4 auth store 的 exchangeOAuthCode

```ts
async exchangeOAuthCode(code: string, state: string) {
  const res = await api.post('/v1/auth/oauth/callback', { code, state })
  this.setTokens(res.accessToken, res.refreshToken)
  this.user = res.user
  this.roles = res.roles
  this.permissions = res.permissions
  this.broadcastChange()
}
```

---

## 9. 人机验证

### 9.1 场景

| 场景 | 方案 |
|---|---|
| 登录失败 3 次 | 图形验证码（滑块 / 点选） |
| 注册 / 改密 | 短信验证码 + 行为验证 |
| 高敏感（转账） | OTP / 生物识别 |
| 评论 / 发帖 | 频率限制 + 行为验证 |

### 9.2 第三方方案（推荐）

| 服务商 | 适用 |
|---|---|
| 极验 GeeTest | 滑块、点选、智能无感 |
| 阿里盾 | 行为风控 |
| 腾讯防水墙 | 国内业务 |
| Google reCAPTCHA | 海外 |

### 9.3 集成示例（极验）

```ts
// composables/useCaptcha.ts
import { loadScript } from '@/utils/script'

let inited = false

export function useCaptcha() {
  async function verify(): Promise<string> {
    if (!inited) {
      await loadScript('https://static.geetest.com/static/tools/gt.js')
      inited = true
    }
    return new Promise((resolve, reject) => {
      // eslint-disable-next-line no-undef
      initGeetest({
        gt: import.meta.env.VITE_GEETEST_ID,
        challenge: await api.getCaptchaChallenge(),
        offline: false,
        new_captcha: true
      }, (captcha: any) => {
        captcha.onSuccess(() => {
          const result = captcha.getValidate()
          resolve(JSON.stringify(result))
        })
        captcha.onError(reject)
        captcha.appendTo('#captcha-box')
      })
    })
  }

  return { verify }
}

// 表单中使用
const { verify } = useCaptcha()
async function submit() {
  const captcha = await verify()  // 用户完成验证后才得 token
  await api.login({ ...credentials, captcha })
}
```

> ⚠️ **禁自实现验证码**：自实现的图形验证码极易被 OCR 破解。

---

## 10. 敏感数据处理

### 10.1 不打印到日志

```ts
const SENSITIVE_KEYS = ['password', 'token', 'secret', 'apiKey', 'idCard', 'bankCard']

function mask(obj: any): any {
  if (typeof obj !== 'object' || obj === null) return obj
  return Object.fromEntries(
    Object.entries(obj).map(([k, v]) => {
      if (SENSITIVE_KEYS.some(s => k.toLowerCase().includes(s.toLowerCase()))) {
        return [k, '***']
      }
      return [k, mask(v)]
    })
  )
}

console.log('User login', mask(credentials))
```

### 10.2 不入 Sentry / 监控

```ts
Sentry.init({
  dsn: '...',
  beforeSend(event) {
    // 移除敏感字段
    if (event.request?.data) {
      event.request.data = maskString(event.request.data)
    }
    return event
  }
})
```

### 10.3 展示脱敏

```ts
export function maskPhone(p: string): string {
  if (!p || p.length < 7) return '***'
  return p.slice(0, 3) + '****' + p.slice(-4)
}

export function maskIdCard(id: string): string {
  if (!id || id.length < 10) return '***'
  return id.slice(0, 6) + '********' + id.slice(-4)
}

export function maskBankCard(card: string): string {
  if (!card || card.length < 8) return '***'
  return card.slice(0, 4) + '******' + card.slice(-4)
}

export function maskEmail(email: string): string {
  if (!email || !email.includes('@')) return '***'
  const [name, domain] = email.split('@')
  return name.charAt(0) + '***@' + domain
}
```

### 10.4 不入 URL

| 字段 | ❌ URL | ✅ Body |
|---|---|---|
| Token | URL 参数 | Header |
| 密码 | URL 参数 | Body |
| 身份证 | URL 参数 | Body |
| 银行卡 | URL 参数 | Body |

---

## 11. 退出登录

### 11.1 完整清理

```ts
async function logout() {
  const auth = useAuthStore()
  try {
    await client.post('/v1/auth/logout')  // 调后端撤销 access_token
  } catch { /* ignore */ }
  auth.cleanup()                           // Pinia store
  sessionStorage.clear()                   // 所有 sessionStorage
  // localStorage 仅清业务数据，保留偏好（主题、语言）
  localStorage.removeItem('cart')
  // service worker 缓存
  if ('caches' in window) {
    const keys = await caches.keys()
    await Promise.all(keys.map(k => caches.delete(k)))
  }
  router.push({ name: 'login' })
}
```

### 11.2 多标签页同步退出

通过 §2.4 的 `storage` 事件机制：其他标签页的 auth store 收到 `auth_changed` 信号后，发现 `access_token` 已清空，自动跳登录页。

---

## 12. 安全检查清单

### 12.1 上线前必查

| # | 项 |
|---|---|
| 1 | Token 存 sessionStorage，禁 localStorage |
| 2 | 用户输入无未净化 `v-html` |
| 3 | axios 拦截器有 401 自动刷新 + Idempotency-Key |
| 4 | 路由守卫有 requiresAuth + roles + permissions |
| 5 | OAuth 回调有 state 校验 + 时间戳 + 一次性 |
| 6 | 退出登录清理彻底（sessionStorage + Pinia + cache） |
| 7 | URL 参数不含敏感字段 |
| 8 | Sentry / 日志不含敏感数据 |
| 9 | 第三方脚本走 SRI（Subresource Integrity） |
| 10 | CSP 头配置完整 |

### 12.2 季度审计

- 依赖漏洞扫描（npm audit / Snyk）
- Token 存储位置 review
- 路由权限清单 review
- 第三方脚本清单 review
- 监控数据脱敏 review
