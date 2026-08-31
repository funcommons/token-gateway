---
name: mc-web-spec
description: 前端开发场景激活（Vue 3 + Element Plus + i18n + 多品牌多主题）。覆盖新建项目、重构、新增页面、API 对接、规范检查。触发词：规范、检查规范、按规范写、新建项目、重构、新建页面、新增品牌、Vue 页面、API 对接、axios、信封。
version: 1.1.0
enabled: true
metadata:
  type: domain-spec
  category: frontend
  tags: [vue3, element-plus, vite, scss, pinia, vue-i18n, axios, typescript, multi-brand]
  language: zh-CN
  spec-version: v1.0
  related-specs:
    - 前端开发规范.md
  related-skills: [mc-api-spec, mc-java-spec]
  author: architecture-team
  last-reviewed: 2026-06-23
  examples:
    - "Vue 页面 axios 报错未捕获"            # 自动激活：API 对接
    - "新建一个订单列表页面"                 # 自动激活：新建页面
    - "新增一个品牌主题"                     # 自动激活：品牌系统
    - "国际化文案怎么加"                     # 自动激活：i18n
    - "TS 类型怎么定义后端响应"              # 自动激活：类型定义
    - "限流后前端怎么重试"                   # 自动激活：限流处理
    - "amount 字段是 number 还是 string"     # 自动激活：金额类型
---

# 前端开发规范

## 0. 用户速查

| 你想 | 入口 |
|---|---|
| 从零搭项目 | 复制 `example/` 目录 → 场景一 |
| 重构现有项目 | 参考 `example/` 对齐 → 场景二 |
| 新增品牌 | 复制 `_mchuan.scss` → 场景二附 |
| 新建页面 | 类似已有页面复制改 / 全新类型读规范 → 场景五 |
| 对接后端 API | 看 §3 场景四（v1.6 信封） |
| 检查规范 | 场景六 P0 必查 5 项 |
| 退出本规范 | 「退出 mc-webui-spec」 |

## 1. 元信息

| 项 | 说明 |
|---|---|
| 技术栈 | Vue 3 + Element Plus + Vite + SCSS + Pinia + Vue I18n |
| 默认风格 | 主题=mchuan / 色彩=明亮 / 语言=中文 |
| **适用** | Vue 3 前端项目（布局 / 样式 / 国际化 / 品牌切换 / API 对接 / 页面创建 / 规范检查） |
| **不适用** | 后端代码（→ mc-java-spec）、API 设计（→ mc-api-spec）、DB（→ mc-database-spec） |
| 退出 | 用户输入「退出 mc-webui-spec」 |

## 2. 全局铁律

1. **UI 文本必须 `t('key')`**，禁止硬编码中文（含 `document.title`）
2. **路由必须 `createWebHistory()`**，禁止 Hash 模式
3. **主题/品牌/语言默认**：mchuan / 明亮 / 中文
4. **后端响应信封 6 字段**（对齐 mc-api-spec v1.6 §4）：`code` / `message` / `data` / `error` / `trace_id` / `timestamp`；HTTP 200 业务码，**禁止用 HTTP status 判断成功失败**
5. **ID / 金额字段必须是 string**（对齐 v1.6 §5.2）：`id: string`、`amount: string`（元 2 位小数），禁 `number`
6. **写操作必须生成 `Idempotency-Key`**（POST/PUT/PATCH/DELETE），UUID v4

## 3. 场景判定

```
当前任务是什么？
├── 从零搭项目              → 场景一：新建项目
├── 重构现有项目            → 场景二：重构（含新增品牌）
├── 对接后端 API            → 场景三：API 对接（v1.6 信封）
├── 纯逻辑（不改 UI/样式）  → 场景四：纯功能实现
├── 新建 Vue 页面           → 场景五：新建页面
└── 检查代码规范            → 场景六：规范检查
```

---

### 场景一：新建项目

复制 `example/` 整个目录为新项目根 → `npm install` → 删 `src/views/spec/` → 改配置（`nav-config.ts` / `brands.ts` / `locales/` / `routes.ts`）→ 锁定 `vue` / `element-plus` 版本。

> example 本身就是规范的实现，复制即合规。

### 场景二：重构项目 + 新增品牌

参考 example 对应模块逐一对齐：

| 重构目标 | 参考 example 文件 |
|---|---|
| 布局（Header/Sidebar/Main） | `src/components/sdk/layout/App*.vue` |
| 品牌样式 | `src/styles/brands/_mchuan.scss` + `_mixins.scss` |
| 主题配色 | `src/styles/_themes.scss` + `_variables.scss` |
| 响应式间距 | `src/styles/index.scss`（`--app-block-pad` 三档） |
| 侧边栏三模式 | `src/components/sdk/layout/AppSidebar.vue` |
| 偏好持久化 | `src/stores/preference.ts` |
| 空态/加载 | `src/composables/useBrandEmpty.ts` / `useBrandLoading.ts` |
| 通知/弹出/抽屉 | `src/components/sdk/common/AppDialog.vue` / `AppDrawer.vue` |
| 国际化 | `src/locales/` + `src/composables/useLocale.ts` |

**新增品牌**：复制 `_mchuan.scss` → `_mybrand.scss` 改变量 → `_brands.scss` import → `brands.ts` 注册 → 可选加专属空态/加载素材。

### 场景三：API 对接（对齐 v1.6）

**axios 拦截器标准模板**（必读）：

```ts
// src/api/client.ts
import axios from 'axios'
import { v4 as uuidv4 } from 'uuid'

const client = axios.create({ baseURL: '/api', timeout: 10000 })

// 请求拦截：注入 Idempotency-Key（写操作）+ X-Trace-Id
client.interceptors.request.use(cfg => {
  if (['post', 'put', 'patch', 'delete'].includes(cfg.method || '')) {
    cfg.headers['Idempotency-Key'] = uuidv4()
  }
  cfg.headers['X-Trace-Id'] = sessionStorage.getItem('trace_id') || uuidv4()
  return cfg
})

// 响应拦截：HTTP 200 + 业务 code 判定
client.interceptors.response.use(
  res => {
    const body = res.data
    // 1. 提取并存储 trace_id（Header + body 双通道）
    const traceId = res.headers['x-trace-id'] || body.trace_id
    if (traceId) sessionStorage.setItem('trace_id', traceId)

    // 2. 业务码判定（HTTP 永远 200）
    if (body.code === 0) return body.data           // 成功
    if (body.code === 10700) throw new PartialSuccessError(body.data)
    throw new BizError(body.code, body.message, body.error)
  },
  err => {
    // 3. HTTP 非 200 = 基础设施异常
    if (err.response?.status === 429 || err.response?.status === 503) {
      const retryAfter = err.response.headers['retry-after']
      throw new RateLimitError(retryAfter)
    }
    throw new InfraError('网络或网关异常', err)
  }
)
```

**TS 类型模板**（必读）：

```ts
// src/types/api.ts
export interface ApiResponse<T = unknown> {
  code: number                              // 0=成功
  message: string
  data: T
  error: ApiError[] | null
  trace_id: string
  timestamp: number
}

export interface ApiError {
  field?: string                            // snake_case
  code?: string                             // 子类型：FORMAT_INVALID 等
  message: string
  value?: unknown
}

// 业务实体：ID/金额必须 string
export interface Order {
  id: string                                // ✅ 不是 number
  order_no: string
  total_amount: string                      // ✅ 元 2 位小数字符串
  currency: string
  status: 'PENDING' | 'PAID' | 'CANCELLED'  // 枚举字符串字面值
  created_at: number                        // Long ms
}

// 分页响应（Offset 模式）
export interface PageResponse<T> {
  list: T[]
  total: number
  page: number
  page_size: number
  has_more: boolean
  summary: Record<string, string | number> | null
}
```

**关键约束**：

| 维度 | 规则 |
|---|---|
| 成功判定 | `body.code === 0`，**禁止** `res.status === 200` 或 `res.ok` |
| 失败判定 | `body.code !== 0`，从 `body.error[]` 取字段错误 |
| 限流处理 | 读 `Retry-After` Header 做指数退避（业务码 10500/10502） |
| 字段命名 | 后端 snake_case → TS 接口保持 snake_case（不要驼峰） |
| ID 类型 | `string`（雪花 ID 防 JS 精度丢失） |
| 金额类型 | `string`，展示用 `parseFloat`，运算用 `decimal.js` |
| 时间类型 | `number`（Long ms），格式化用 `dayjs` |

### 场景四：纯功能实现

不涉及 UI/样式/品牌/响应式时（API 对接 / 业务逻辑 / 工具函数 / 类型定义），只需遵守 §2 全局铁律 + 场景三的 API 对接规则。

### 场景五：新建 Vue 页面

**先判断**：用 `AskUserQuestion` 问「新页面和已有页面是否类似？」

| 答案 | 流程 |
|---|---|
| 类似已有页面 | 直接复制最接近的页面，改数据/文案 |
| 全新类型 | 读规范 + 按页面类型查必查章节 |

**全新页面的组件选择**：

| 需求 | 组件 |
|---|---|
| 页面标题区 | `HeaderSection` |
| 带标题功能块 | `TitledSection` |
| 内容卡片行 | `RowCard` |
| 白底圆角功能块 | `WorkSection` |
| KPI 指标 | `KpiSection` / `KpiLayout` |
| 弹出框 / 抽屉 | `AppDialog` / `AppDrawer` |
| 设置标签页 | `AppSettingsTabs` |

**核心规则**：Section 不能套 Section；布局组件 padding/margin 为 0；颜色/圆角/阴影用 CSS 变量不硬编码；空态用 `useBrandEmpty`，加载用 `useBrandLoading`。

### 场景六：规范检查

**P0 必查 5 项**（不达标立即修）：

| # | 检查项 | 检查方式 |
|---|---|---|
| 1 | 路由用 `createWebHistory()` | 读 `src/router/index.ts`，无 `createWebHashHistory()` |
| 2 | 无硬编码中文 | 搜索 `.vue`/`.ts` 中文字符（排除注释和 `locales/`） |
| 3 | axios 拦截器按场景三模板 | 检查 `src/api/client.ts` 业务码判定 + trace_id 提取 |
| 4 | ID/金额字段为 string | 检查 `src/types/api.ts` 实体类型 |
| 5 | 写操作带 Idempotency-Key | 检查 axios 请求拦截 |

**P1/P2/P3 检查项**：参考 `./前端开发规范.md` §11（响应式 / 品牌切换 / 侧边栏模式 / Section 嵌套 / 磁吸定位 / 样式变量 等）。

## 4. 关键文件索引

| 文档 / 目录 | 用途 | 活跃版本 |
|---|---|---|
| `./前端开发规范.md` | 完整规范文档 | v1.0 |
| `./example/` | 脚手架起点（直接复制） | - |
| `src/styles/brands/_mchuan.scss` | 品牌变量定义 | - |
| `src/styles/_themes.scss` / `_variables.scss` | 主题 / 全局变量 | - |
| `src/styles/_mixins.scss` | `define-brand` 等 mixin | - |
| `src/styles/index.scss` | 响应式间距 / 布局 helpers | - |
| `src/stores/preference.ts` | theme/brand/locale 偏好持久化 | - |
| `src/config/brands.ts` | 品牌注册表 | - |
| `src/config/nav-config.ts` | 导航菜单 | - |
| `src/components/sdk/layout/` | AppHeader / AppSidebar / AppMain | - |
| `src/components/sdk/common/` | Section / RowCard / AppDialog / AppDrawer | - |
| `src/composables/useBrandEmpty.ts` / `useBrandLoading.ts` | 空态 / 加载 | - |
| `src/composables/useResponsive.ts` | 响应式断点 | - |
| `src/api/client.ts` | axios 封装（场景三模板） | - |
| `src/types/api.ts` | API 类型定义（v1.6 对齐） | - |

## 5. 与后端规范的关系

| 主题 | 前端职责 | 后端规范位置 |
|---|---|---|
| 响应信封 6 字段 | 解析 + 业务码判定 | mc-api-spec v1.6 §4 |
| HTTP 200 业务码 | 不用 status 判成功失败 | mc-api-spec v1.6 §3 |
| 错误码体系 | 按 code 分类处理 | mc-api-spec v1.6 §7 |
| `X-Trace-Id` | 提取并存 session | mc-api-spec v1.6 §5.4 |
| `Retry-After` / `X-RateLimit-*` | 限流时指数退避 | mc-api-spec v1.6 §5.4、§7.9 |
| `Idempotency-Key` | 写操作生成 UUID | mc-api-spec v1.6 §5.4 |
| ID/金额 String | TS 类型标 string | mc-api-spec v1.6 §5.2 |
| 字段命名 snake_case | 保持不变 | mc-api-spec v1.6 §5.1 |
