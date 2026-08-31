---
name: using-sdk
description: 前端开发规规范. 前端 SDK (Fc* 组件库) 使用指南. 覆盖新建页面、选组件、套模板、对接主题/品牌. 触发词: sdk、fc 组件、新建页面、组件库、用组件拼页面、页面模板、FcSection、FcButton、FcTable、不写 css、主题切换、品牌切换、FcThemeProvider、侧栏菜单、导航菜单、sdk 用户.
version: 1.0.0
enabled: true
metadata:
  type: usage-guide
  category: frontend
  tags: [vue3, fc-sdk, element-plus, multi-brand, theme, i18n, sdk]
  language: zh-CN
  canonical-ref: src/components/sdk/README.md
  related-skills: [mc-web-spec]
  author: frontend-team
  last-reviewed: 2026-07-19
  examples:
    - "用 Fc 组件帮我拼一个列表页"
    - "侧栏菜单怎么接入?"
    - "新建一个表单弹窗"
    - "我想切主题/品牌, 不写 CSS"
    - "sdk 用户从零搭一个页面, 不应该看到 el- 组件"
    - "怎么避免写 CSS?"
---

# Fc SDK 使用指南

## 0. 用户速查

| 你想做 | 入口 |
|---|---|
| 必读硬性约束 | §1 强制规则 |
| 前端开发规范 (原则 + 技术栈) | §2 |
| 5 分钟从零接入 (主题 + 路由) | §3 |
| 新建页面 (列表 / 详情 / 表单) | §4 (套模板) |
| 选组件 (我要展示/筛选/浮层/输入) | §5 (速查表) |
| 接入侧栏菜单 (nav) | §6 |
| 切换主题 / 品牌 | §7 |
| 改样式 / 加 brand | §8 (review 必查) |
| **完整 API + 模板** | 跳到 `src/components/sdk/README.md` (本文件是其浓缩版) |

> **核心承诺**: 用 SDK 组件拼页面 = **0 行 CSS** (颜色/圆角/阴影/间距全走 token)。

---

## 1. 强制规则 (硬性约束 — 违反 = 退回 el- 时代)

> **本节优先于所有其它章节**. 项目初始化 / 写新页面前必读.

1. **必用 `./sdk` 目录的组件** — 拼页面时一律从 `src/components/sdk/index.ts` 取 Fc* 组件.
2. **项目初始化时把整个 sdk 目录 copy 到 `src/components`** — 不要挑拣是否用到, 一次性铺到位. 命令:
   ```bash
   cp -r sdk/* src/components/
   ```
3. **必读 `sdk/README.md` 文档** — 任何 Fc 组件使用前先查 README 的 prop / 槽 / emit 表 (§0 / §3 速查表是浓缩入口).
4. **主题组件必用** — `FcThemeProvider` / `FcThemeSwitcher` 是接入必备, 不允许自写主题/品牌切换逻辑.
5. **其它组件绝对优先** — 有 Fc 组件就不写自实现 / 不退回 `el-*` / 不重写第二遍.

**判错红线** (code review 时直接打回):

| 违规 | 正确做法 |
|---|---|
| `<el-button>` / `<el-table>` / `<el-card>` | 换成对应 `FcButton` / `FcTable` / `FcSection` |
| 自写主题切换 (color-mix + 监听 localStorage) | 用 `FcThemeProvider` + `FcThemeSwitcher` |
| 业务页手写导航菜单 `<el-sub-menu>` | 用 `FcSidebarNav` + `useSidebarNavItems` |
| 新项目没 copy sdk 整个目录 | 先 `cp -r sdk/* src/components/` 再开写 |
| 用了 sdk 组件但没读 README | 至少扫一遍 §0 速查表 + 该组件章节 |

---

## 2. 前端开发规范 (Frontend Development Specification)

### 2.1 原则 (Principles)

**路由模式 (Routing)**
- **HTML5 History 模式**: 所有项目必须采用 HTML5 History 模式, 禁止 Hash 模式.
- **服务端配合**: 生产 Nginx 必须配 `try_files $uri $uri/ /index.html`, 避免刷新 404.

**国际化 (i18n)**
- **默认支持**: 架构设计之初必须集成 i18n, 默认内置中英双语 (`zh-CN` / `en-US`).
- **静态文本规范**: 禁止硬编码中文字符串, 全部走 `t('key')`.
- **语言切换**: 即时无刷新切换 + 持久化 LocalStorage.

**主题动态更换 (Theme)**
- **动态配置**: 主题必须支持动态无刷新更换. 所有组件样式基于 CSS Variables 驱动, 通过 `html[data-brand]` + `html[data-theme]` 切换.
- **主题维度**: 切换由 **品牌 (Brand)** + **配色 (Theme)** 共同决定.
  - **品牌 (Brand)**: 决定圆角 / 阴影 / 字体 / 主色. 当前默认 `mchuan`.
  - **配色 (Theme)**: 决定明暗背景 / 文字色 / 边框色. 当前已入册 3 种: `light` / `dark` / `orange-black`.

**响应式 (Responsive)**

| 设备 | 断点 |
|---|---|
| Mobile | `screen < 768px` |
| Tablet | `768px <= screen < 1024px` |
| Desktop | `screen >= 1024px` |

- 鼠标 + 触控双适配, 最小点击热区 44px x 44px.

### 2.2 技术栈 (Tech Stack)

"已入册" + "审核入册" 双轨制. 非"已入册"技术使用前必须提交技术委员会审核.

```
├── 已入册 (推荐全项目使用)
│   ├── TypeScript    (静态类型安全, 全面启用严格模式)
│   ├── Vue 3         (组合式 API Setup 语法糖)
│   ├── Element Plus  (版本 2.14.1, 基础 UI 组件)
│   └── micro-app     (微前端框架, 子应用聚合)
└── 审核入册
    └── 任何其他第三方库 (特定图表库 / 动效库 / 状态管理替代方案等)
```

**与 SDK 的关系**: Element Plus 仅作底层依赖存在, 业务层一律走 Fc* 组件 (见 §1 强制规则).

---

## 3. 场景一：从零接入 (5 分钟)

```ts
// main.ts 顶部, 只加这一行 (全局样式 + EP 覆写 + 8 brand + 主题)
import '@/components/sdk/theme/theme.scss'
```

```vue
<!-- App.vue 顶层包 Provider, 主题/品牌自动持久化 -->
<template>
  <FcThemeProvider>
    <router-view />
  </FcThemeProvider>
</template>

<script setup lang="ts">
import { FcThemeProvider } from '@/components/sdk'
</script>
```

启动后默认 `brand=mchuan` + `theme=light`, 用户切了之后写 localStorage (`fc-theme-provider`), 刷新仍保持。

**OEM 集成**: 走 OEM 白标时, `initialBrand` / `initialTheme` 用 OEM config 兜底:

```vue
<FcThemeProvider
  :initial-brand="oem.config.brand || 'mchuan'"
  :initial-theme="oem.config.theme || 'light'"
  v-model:brand="brand"
  v-model:theme="theme"
>
```

---

## 4. 场景二：新建页面 — 套模板

**别从零写**, README 末尾有 5 个模板. 下面是 3 个最常用的精简版.

### 模板 A: 列表页 (表头 + 筛选 + 表格 + 分页)

```vue
<template>
  <div class="app-page">
    <FcSectionHeader :title="t('page.title')" :back="true" @back="router.back()">
      <template #actions>
        <FcButton variant="primary" :icon="Plus" @click="onCreate">
          {{ t('common.create') }}
        </FcButton>
      </template>
    </FcSectionHeader>

    <FcFilterBar>
      <FcFilterButton
        v-for="tab in tabs" :key="tab.value"
        :active="active === tab.value" @click="active = tab.value"
      >
        {{ tab.label }}
      </FcFilterButton>
      <FcFilterBarDivider />
      <FcInput v-model="kw" :placeholder="t('common.search')" clearable @enter="onSearch" />
    </FcFilterBar>

    <FcSection>
      <FcTable :data="rows" :loading="loading" stripe>
        <el-table-column prop="name" :label="t('col.name')" />
        <el-table-column :label="t('col.status')">
          <template #default="{ row }">
            <FcTag :color="row.active ? 'success' : 'gray'" size="sm">
              {{ row.active ? '启用' : '禁用' }}
            </FcTag>
          </template>
        </el-table-column>
        <el-table-column :label="t('col.actions')" fixed="right">
          <template #default="{ row }">
            <FcButton variant="text" size="sm" @click="edit(row)">{{ t('common.edit') }}</FcButton>
            <FcButton variant="text" size="sm" @click="del(row)">{{ t('common.delete') }}</FcButton>
          </template>
        </el-table-column>
      </FcTable>

      <FcPagination
        v-model:current-page="page" v-model:page-size="pageSize" :total="total"
        style="margin-top: 12px; justify-content: flex-end"
      />
    </FcSection>
  </div>
</template>

<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import {
  FcSectionHeader, FcSection, FcFilterBar, FcFilterBarDivider, FcFilterButton,
  FcTable, FcTag, FcButton, FcInput, FcPagination,
} from '@/components/sdk'
</script>
```

### 模板 B: 表单弹窗

```vue
<FcButton variant="primary" @click="open = true">{{ t('common.create') }}</FcButton>

<FcDialog v-model:open="open" :title="t('form.title')" width="540px" append-to-body>
  <el-form :model="form" :rules="rules" label-width="80px">
    <FcFormItem :label="t('form.name')" prop="name">
      <FcInput v-model="form.name" maxlength="50" show-word-limit />
    </FcFormItem>
    <FcFormItem :label="t('form.type')" prop="type">
      <FcSelect v-model="form.type" :options="typeOptions" />
    </FcFormItem>
    <FcFormItem :label="t('form.enabled')">
      <FcSwitch v-model="form.enabled" active-text="开" inactive-text="关" />
    </FcFormItem>
  </el-form>
  <template #footer>
    <FcButton @click="open = false">{{ t('common.cancel') }}</FcButton>
    <FcButton variant="primary" :loading="saving" @click="save">{{ t('common.save') }}</FcButton>
  </template>
</FcDialog>
```

### 模板 C: 详情页 (卡片 + Tab + 空态)

```vue
<FcSectionHeader :title="data?.name || ''" :back="true" @back="router.back()">
  <template #actions>
    <FcButton variant="danger" :loading="deleting" @click="del">{{ t('common.delete') }}</FcButton>
  </template>
</FcSectionHeader>

<FcSection v-if="loading"><FcSkeleton variant="card" :rows="5" /></FcSection>

<FcSection v-else-if="data">
  <FcTabsPanel v-model="tab" :tabs="tabs">
    <template #tab-info>
      <div class="info-grid">
        <div class="info-item"><span class="info-label">{{ t('info.id') }}</span><span>{{ data.id }}</span></div>
      </div>
    </template>
    <template #tab-history>
      <FcEmpty v-if="!history.length" :title="t('history.empty')" />
    </template>
  </FcTabsPanel>
</FcSection>

<FcEmpty v-else type="error" :title="t('error.not-found')" />

<style scoped lang="scss">
/* 仅布局类 CSS (grid/flex), 颜色圆角禁止 */
.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.info-item { display: flex; gap: 8px; font-size: 13px; }
.info-label { color: var(--app-text-secondary); min-width: 80px; }
</style>
```

---

## 5. 场景三：选组件 — 速查表

| 你要 | 用 | 替代 (禁用) |
|---|---|---|
| 页面分区容器 | `FcSection` / `FcSectionCard` | `el-card` |
| 页面顶部标题行 | `FcSectionHeader` | 手写 `<h1>` + 返回 |
| 表格 | `FcTable` | `el-table` |
| 按钮 | `FcButton` (`variant: primary/secondary/text/danger`) | `el-button` |
| 输入框 | `FcInput` (含 placeholder/error/clearable) | `el-input` |
| 下拉 | `FcSelect` | `el-select` |
| 开关 | `FcSwitch` (走品牌 token) | `el-switch` |
| 表单项 | `FcFormItem` | `el-form-item` |
| 标签 / chip | `FcTag` (6 色 + solid + closable) | `el-tag` |
| 筛选条 | `FcFilterBar` + `FcFilterButton` + `FcFilterBarDivider` | `el-tabs` |
| 分段单选 | `FcSegmented` | `el-radio-group` |
| Tab + 内容 | `FcTabsPanel` | `el-tabs` |
| 分页 | `FcPagination` | `el-pagination` |
| 对话框 | `FcDialog` (可拖拽 / 调尺寸) | `el-dialog` |
| 抽屉 | `FcDrawer` (4 方向) | `el-drawer` |
| 气泡 | `FcPopover` | `el-popover` |
| 提示 | `FcTooltip` (含 danger 变体) | `el-tooltip` |
| 确认弹窗 | `FcConfirm` | `el-message-box` |
| 右键菜单 | `FcContextMenu` | 手写 |
| 空态 | `FcEmpty` (5 种 type: no-data/error/processing/search/no-result) | `el-empty` |
| 骨架屏 | `FcSkeleton` (text/rect/avatar/card) | `el-skeleton` |
| 图片 | `FcImage` (含 shimmer/失败回退/比例锁) | `<img>` + 自写样式 |
| 头像 | `FcAvatar` (name 哈希渐变兜底) | 手写 |
| 状态徽章 | `FcStatusBadge` (5 tone + 脉冲点) | 手写 |
| 拖拽网格 | `FcReorderableGrid` | 手写 |
| 拖放文件 | `FcDropZone` (业务无关, 只校验吐文件) | 手写 |
| 图片选择器 | `FcImagePicker` (上传/URL/最近/粘贴/多选) | `el-upload` |
| 顶部栏 | `FcHeader` | 手写 |
| 侧栏壳 | `FcSidebar` (3 折叠模式) | 手写 |
| 侧栏折叠按钮 | `FcSidebarToggle` | 手写 |
| 侧栏导航 | `FcSidebarNav` + `useSidebarNavItems` | 手写 `<el-sub-menu>` |
| 响应式侧栏 | `FcSidePanel` (桌面定宽 / 移动 FAB) | 手写 |
| 主区壳 | `FcMain` | 手写 |
| 主题切换 | `FcThemeProvider` + `FcThemeSwitcher` | 手写 + copy brand scss |

> 完整 prop/槽/emit/event 表格见 `src/components/sdk/README.md`.

---

## 6. 场景四：接入侧栏菜单

**最小用法** (一行生成 nav items):

```ts
import { useSidebarNavItems } from '@/components/sdk'
import { resolveIcon } from '@/utils'
import { isFeatureEnabled } from '@/config/features'
import { Files, MagicStick, Wallet } from '@element-plus/icons-vue'

const navItems = useSidebarNavItems({
  iconResolver: (name) => resolveIcon(name),
  features: isFeatureEnabled,                // meta.feature 校验
  customFilter: (r) => !r.meta?.hideInMenu,
  topLevels: [
    { routeNames: ['Home', 'Chat'] },        // 顶层单条
  ],
  groups: [
    { id: 'plaza',  labelKey: 'sidebar.plaza',  icon: Files,
      routeNames: ['Inspiration', 'TemplatePlaza'] },
    { id: 'create', labelKey: 'sidebar.create', icon: MagicStick,
      routeNamePrefix: 'Create' },           // 前缀匹配
    { id: 'assets', labelKey: 'sidebar.assets', icon: Wallet,
      routeNamePrefix: 'Assets' },
  ],
})
```

**模板里** (绑 active path + emit 跳转):

```vue
<FcSidebarNav
  :items="navItems"
  :active-path="route.path"
  :collapse="sidebarCollapsed"
  :default-openeds="['sub-create', 'sub-assets']"
  @select="(path) => router.push(path)"
/>
```

**角色过滤** (`visible` 返回 boolean):

```ts
groups: [
  { id: 'admin', labelKey: 'sidebar.admin', icon: Setting,
    routeNamePrefix: 'Admin',
    visible: () => userStore.userInfo?.role === 'admin' },
]
```

**叶子额外过滤** (admin 排除部分路由):

```ts
groups: [
  { id: 'admin', labelKey: 'sidebar.admin', icon: Setting,
    routeNamePrefix: 'Admin',
    leafFilter: (r) => r.name !== 'AdminModels' },
]
```

底层函数 (`filterRoutes` / `buildNavItems`) 见 README §layout.

---

## 7. 场景五：主题 / 品牌切换

**只需 3 步** (场景一已完成前 2 步, 这里讲第 3 步):

```vue
<!-- 顶栏放个按钮, 点击弹出 popover -->
<FcThemeSwitcher v-model:brand="brand" v-model:theme="theme" variant="popover" />

<!-- 或直接展开 (设置抽屉内) -->
<FcThemeSwitcher v-model:brand="brand" v-model:theme="theme" variant="inline" />

<!-- 移动端用 drawer 变体 -->
<FcThemeSwitcher v-model:brand="brand" v-model:theme="theme" variant="drawer" />
```

**Provider provide 上下文** (`fc-theme` 注入), 子组件可直接拿当前 brand/theme:

```ts
import { inject } from 'vue'
const theme = inject('fc-theme')
console.log(theme.currentBrand.value, theme.currentTheme.value)
```

**业务临时改色** (不持久化, 一次性场景):

```vue
<FcSection :style="{ '--app-primary': '#ff0000' }">
  <FcButton variant="primary">红色按钮</FcButton>
</FcSection>
```

---

## 8. 强制规范 (Review 必查)

### 6.1 EP 组件禁用清单

业务页**禁止**直接用以下 EP, 必须用 Fc 对应:

```text
禁用: el-card / el-dialog / el-drawer / el-popover / el-tooltip
     el-empty / el-skeleton / el-tag / el-button (主操作)
     el-input / el-select / el-form-item / el-switch
     el-table / el-tabs (页内) / el-pagination
     el-upload (图片) / el-message-box
     <img class="cover"> + 自写加载失败 / 自写 .xxx-card / 自写 .xxx-tag
```

Code review: `grep -nE "<el-card|<el-dialog|<el-table|<el-tag" src/views/**/*.vue`.

### 6.2 CSS 写与不写的边界

**✅ 允许** (布局/间距/字号/动画):

```scss
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.row    { display: flex; align-items: center; gap: 8px; }
.title  { font-size: 16px; font-weight: 600; }
```

**❌ 禁止** (颜色/圆角/阴影/边框 — 全部走 token):

```scss
.bad { color: #1677ff; background: #f5f5f5; }            /* ❌ 硬编码色值 */
.bad { border-radius: 8px; }                              /* ❌ 硬编码圆角 */
.bad { box-shadow: 0 2px 8px rgba(0,0,0,0.1); }           /* ❌ 硬编码阴影 */
.bad { border: 1px solid #e5e5e5; }                      /* ❌ 硬编码边框 */
```

**✅ 必须用 token**:

```scss
.good { color: var(--app-text); background: var(--app-bg-card); }
.good { border-radius: var(--app-radius-md); }
.good { box-shadow: var(--app-shadow-sm); }
.good { color: color-mix(in srgb, var(--app-primary) 12%, transparent); }
```

**auto-memory 提醒**: `WorkSection` (即 `FcSection`/`FcSectionCard`) 内**禁止嵌套带 border/bg 的盒子**, 用 `el-divider` 或 `margin` 分隔.

### 6.3 EP 浮层覆盖

如必须覆盖 `el-dialog` / `el-drawer` 内部 EP 样式, **必须** `!important` + 双类选择器 (与 `_ep-overrides.scss` 一致). 但 99% 场景用 `FcDialog` 的 `dialogClass`/`bodyClass`/`modalClass` props 即可, 不必写 `:deep`.

---

## 9. 常见坑

| 坑 | 解法 |
|---|---|
| 按钮 hover 色写死 | 用 `:hover` + `color-mix(in srgb, var(--app-primary) 10%, transparent)`, 或纯 token 自带 hover 态 (`FcButton` 已处理) |
| 输入框错误态自定义 | `FcInput` 有 `error` prop, 不要写 `:class="{ 'is-error': err }"` |
| 弹窗遮罩深浅 | `FcDialog` 用 `modalClass` 控制, 不要再 `:deep(.el-overlay)` |
| 切换 brand 后某些组件没变色 | 检查是否还在用 `<img>` 而不是 `FcImage` / 还在用 `el-button` 而不是 `FcButton` |
| 侧栏菜单顺序不对 | `routeNames` 数组顺序就是菜单顺序; `routeNamePrefix` 走 router 顺序 — 想控顺序就用 `routeNames` |
| 主题切换后首屏白闪 | `main.ts` 启动期同步调一次 `applyToRoot(defaultBrand, defaultTheme)` (Provider 已自动处理, 通常没事) |
| i18n key 缺失警告 | 检查 `src/locales/zh-CN.ts` 和 `en-US.ts` 是否同时加; FcSwitch 等内置组件的 i18n 也走全局 |
| `el-form` 校验不触发 | 必须用 `<el-form :model :rules>`, `FcFormItem` 仅是 `el-form-item` 的样式薄封装 |

---

## 10. 新增 SDK 组件流程 (维护者用)

1. 在 `sdk/<category>/Fc<Name>.vue` 创建
2. 在 `sdk/index.ts` 加导出
3. 在 `src/components/sdk/README.md` 速查表加一行 + 新章节
4. 业务页替换散件实现
5. ESLint 加 `no-restricted-syntax` 拦截新 EP 组件

### 新增 brand 步骤

1. 在 `sdk/theme/brands/` 新建 `_<name>.scss`, `@include define-brand(<name>, $tokens)`
2. 在 `sdk/theme/brands/_index.scss` 加 `@use '<name>'`
3. 在 `sdk/theme/brands/_index.ts` 的 `BRANDS` 数组追加 `{ id, label, desc, accent }`
4. 完成 — 业务侧零改动, FcThemeSwitcher 自动列出

---

## 11. 跳到完整文档

`src/components/sdk/README.md` — 1450 行, 含每个组件的 prop 表/槽/emit + 5 个完整模板.

**找不到组件怎么办**: 在 README 用 `Ctrl-F` 搜 `Fc<关键词>` 或查第 3 节速查表.

**还是不会**: 看 `views/` 下已有页面 (e.g. `views/admin/tags.vue`, `views/plaza/inspiration/index.vue`) — 都是 0-CSS 范本.