<script setup lang="ts">
// 规范 · 二、布局 — 完整镜像 docs/规范/二、布局.md 内容
import HeaderSection from '@/components/sdk/common/HeaderSection.vue'
import TitledSection from '@/components/sdk/common/TitledSection.vue'
import RowCard from '@/components/sdk/common/RowCard.vue'

const asciiDiagram = `┌──────────────────────────────────────────────────────────────┐
│                          AppHeader                           │
├────────────┬─────────────────────────────────────────────────┤
│            │  [ToolBar]                                      │
│            │  ┌────────────────────────────────────────────┐ │
│            │  │                  Section 1                 │ │
│ AppSidebar │  ├────────────────────────────────────────────┤ │
│            │  │                  Section 2                 │ │
│            │  └────────────────────────────────────────────┘ │
│            │  [FooterBar]                   [RightSidebar]   │
└────────────┴─────────────────────────────────────────────────┘`

const decisionTree = `需要顶部品牌区 + 强导航?
├── 是 → 🏢 APP 模式      (SaaS 后台 / 复杂业务系统)
└── 否 → 需要多级模块导航?
         ├── 是 → 📦 模块模式  (子系统 / 控制面板)
         └── 否 → 📄 单页模式  (大屏 / 报表 / 向导 / 营销页)`

const modeFlow = `手机端 (< 768px)        → 强制 Drawer 模式 (无其他选择)
平板端 (768-1023px)     → 默认 Icon Mini, 用户可手动切到 Full
电脑端 (≥ 1024px)       → 默认 Full Expanded, 用户可手动切到 Icon Mini`

const responsiveScss = `/* 手机端 (< 768px) */
@media screen and (max-width: 767px) {
  .app-main-block,
  .app-main > .section {
    padding: 4px !important;
    margin-bottom: 4px !important;
  }
}

/* 平板端 (768-1023px) */
@media screen and (min-width: 768px) and (max-width: 1023px) {
  .app-main-block,
  .app-main > .section {
    padding: 8px !important;
    margin-bottom: 8px !important;
  }
}

/* 电脑端 (>= 1024px) */
@media screen and (min-width: 1024px) {
  .app-main-block,
  .app-main > .section {
    padding: 16px !important;
    margin-bottom: 16px !important;
  }
}`

const barStickyScss = `.app-main {
  position: relative;

  .app-toolbar   { position: sticky; top: 0;     }  // 顶部
  .app-footerbar { position: sticky; bottom: 0;  }  // 底部
  .app-rightbar  { position: sticky; right: 0;   }  // 右侧
}`

const layoutZeroScss = `/* 布局组件在工作区内必须 0 间距 */
.app-main {
  .el-row,
  .el-col,
  .el-tabs__content {
    padding: 0 !important;
    margin: 0 !important;
  }
}`

const stickyHeaderScss = `.app-header {
  position: sticky;
  top: 0;
  z-index: 1000;  // 任何设备都磁吸
}`

const sidebarStateTs = `// 状态持久化: 折叠/展开偏好存 LocalStorage
const preference = usePreferenceStore()
preference.sidebarCollapsed  // true = Icon Mini, false = Full Expanded
localStorage.setItem('sidebarCollapsed', String(preference.sidebarCollapsed))`

// §2.1.1 顶部栏全局常量
const headerConstants = [
  { name: 'Z_INDEX',                 value: '1000', source: '规格 3.2.1', desc: 'AppHeader 必须在 sidebar / drawer 之上' },
  { name: 'MOBILE_BREAKPOINT_PX',    value: '768',   source: '规格 1.4',   desc: '移动端断点, 与 BREAKPOINTS.tablet 同源' },
  { name: 'AVATAR_SIZE',             value: '32',    source: '当前 DEMO',  desc: '用户头像尺寸 (el-avatar :size prop)' },
  { name: 'DEMO_NOTIFICATION_COUNT', value: '3',     source: '当前 DEMO',  desc: '通知未读数 (示例数据)' },
]

// §2.1.2 顶部栏布局尺寸
const headerLayoutVars = [
  { name: '--app-header-height',           value: '56px',  desc: '容器高度 (ldx2 覆写 60px)' },
  { name: '--app-header-padding',          value: '0 16px', desc: '水平内边距' },
  { name: '--app-header-gap',              value: '16px',  desc: '三段间距' },
  { name: '--app-header-gap-left',         value: '12px',  desc: '左段间距' },
  { name: '--app-header-gap-right',        value: '4px',   desc: '右段间距' },
  { name: '--app-header-gap-logo',         value: '8px',   desc: 'logo 图标与文字间距' },
  { name: '--app-header-touch-target',     value: '40px',  desc: '触控目标 (Apple HIG ≥44px, 项目折中 40px)' },
  { name: '--app-header-logo-size',        value: '24px',  desc: 'logo 图标尺寸' },
  { name: '--app-header-logo-subtitle-size', value: '11px', desc: 'logo 副标题字号' },
  { name: '--app-header-logo-line-height', value: '1.2',   desc: 'logo 行高' },
  { name: '--app-header-search-max-width', value: '480px', desc: '搜索框最大宽度' },
  { name: '--app-header-user-padding',     value: '4px',   desc: '用户菜单内边距' },
]

const headerVisualVars = [
  { name: '--app-header-bg',             value: 'var(--el-bg-color)',                  desc: '容器背景',                        override: 'apple rgba(255,255,255,.72) / ldx2 rgba(28,28,30,.85) / vonnex rgba(255,255,255,.7)' },
  { name: '--app-header-border-color',   value: 'var(--el-border-color-extra-light)',  desc: '下边框色',                          override: 'apple rgba(0,0,0,.08)' },
  { name: '--app-header-border-width',   value: '1px',                                  desc: '下边框宽度',                        override: '—' },
  { name: '--app-header-shadow',         value: 'none',                                  desc: '容器阴影',                          override: 'apple 0 1px 0 rgba(0,0,0,.04)' },
  { name: '--app-header-backdrop',       value: 'none',                                  desc: '模糊 (HIG 玻璃)',                  override: 'apple/ldx2 blur(20px) / vonnex blur(40px)' },
  { name: '--app-header-user-hover-bg',  value: 'var(--el-fill-color-light)',           desc: '用户菜单 hover',                     override: '—' },
]

// §2.2 侧边栏
const sidebarConstants = [
  { name: 'WIDTH_DEFAULT',         value: '240px',     desc: '默认展开宽度' },
  { name: 'WIDTH_COLLAPSED',      value: '64px',      desc: '折叠后图标宽度' },
  { name: 'WIDTH_MIN / MAX',       value: '200 / 400px', desc: '拖拽夹逼范围' },
  { name: 'TOGGLE_SIZE',           value: '32px',      desc: '右下角折叠按钮' },
  { name: 'ITEM_HEIGHT_L1',        value: '40px',      desc: '一级菜单项高度 (含折行)' },
  { name: 'ITEM_HEIGHT_L2',        value: '36px',      desc: '二级菜单项高度' },
  { name: 'RESIZE_HANDLE_WIDTH',   value: '4px',       desc: '拖拽 handle 宽度 (仅鼠标)' },
]

const sidebarVars = [
  { name: '--app-sidebar-bg',              value: '#ffffff',           desc: '容器背景' },
  { name: '--app-sidebar-border-color',    value: '#dcdfe6',           desc: '右边框色' },
  { name: '--app-sidebar-item-text',       value: '#1f1f1f',           desc: '菜单文字色' },
  { name: '--app-sidebar-item-hover-bg',   value: 'rgba(0,0,0,.04)',   desc: 'hover 背景' },
  { name: '--app-sidebar-item-active-bg',  value: '#409eff',           desc: '激活项背景 (EP primary blue)' },
  { name: '--app-sidebar-item-active-text', value: '#ffffff',          desc: '激活项文字色' },
  { name: '--app-sidebar-divider-text',    value: '#909399',           desc: '系统分隔符文字色 (text-tertiary)' },
  { name: '--app-sidebar-toggle-hover-bg', value: 'rgba(0,0,0,.04)',   desc: '折叠按钮 hover' },
]

// §2.3 工作区
const mainVars = [
  { name: '--app-main-bg',           value: '#f5f7fa',                desc: '工作区背景 (浅灰, 区分于 sidebar 白底)' },
  { name: '--app-main-padding-x',    value: '16/8/4px',               desc: '水平内边距 (响应式, 同 §4.1)' },
  { name: '--app-main-padding-y',    value: '16/8/4px',               desc: '垂直内边距 (响应式)' },
  { name: '--app-main-block-gap',    value: '16/8/4px',               desc: '子区块间距 (响应式)' },
]

// §2.5 功能块
const sectionConstants = [
  { name: 'RADIUS_CARD',  value: '10px',          desc: '功能块圆角 (Apple 风格)' },
  { name: 'SHADOW_CARD',   value: '0 4px 12px rgba(0,0,0,.06), 0 2px 4px rgba(0,0,0,.04)', desc: '功能块阴影 (HIG 双层柔和)' },
  { name: 'BORDER_WIDTH',  value: '1px',           desc: '功能块边框宽度' },
  { name: 'PADDING_X',     value: '16px',          desc: '功能块水平内边距' },
]

const sectionVars = [
  { name: '--app-section-bg',          value: '#ffffff',   desc: '功能块背景' },
  { name: '--app-section-border-color', value: '#dcdfe6',   desc: '功能块边框色' },
  { name: '--app-section-radius',      value: '10px',      desc: '圆角' },
  { name: '--app-section-shadow',      value: '0 4px 12px rgba(0,0,0,.06), 0 2px 4px rgba(0,0,0,.04)', desc: '阴影' },
  { name: '--app-section-title-color', value: '#1f1f1f',   desc: '标题文字色' },
  { name: '--app-section-desc-color',  value: '#5e5e5e',   desc: '副标题文字色 (text-secondary)' },
]

// §2.6 3 bar
const barConstants = [
  { name: 'Z_INDEX_TOOLBAR',      value: '10',    desc: '工具栏层级 (在内容之上)' },
  { name: 'Z_INDEX_FOOTERBAR',    value: '10',    desc: '底部栏层级' },
  { name: 'Z_INDEX_RIGHTSIDEBAR', value: '10',    desc: '右侧栏层级' },
  { name: 'HEIGHT_DEFAULT',       value: '48px',  desc: '工具栏/底部栏高度' },
  { name: 'WIDTH_RIGHTSIDEBAR',   value: '320px', desc: '右侧栏宽度 (含 trigger button)' },
]

const barVars = [
  { name: '--app-toolbar-bg',    value: '#ffffff', desc: '工具栏背景' },
  { name: '--app-footerbar-bg',  value: '#ffffff', desc: '底部栏背景' },
  { name: '--app-rightbar-bg',   value: '#ffffff', desc: '右侧栏背景' },
  { name: '--app-bar-border-color', value: '#dcdfe6', desc: '3 bar 共用边框色' },
  { name: '--app-bar-shadow',    value: '0 2px 8px rgba(0,0,0,.04)', desc: '3 bar 共用阴影' },
]

const modeRows = [
  { mode: '🫥 侧边抽屉模式 (Drawer)', width: '全屏 (平时隐藏)', behavior: '菜单默认完全隐藏 / 视口左上角触发按钮 / 点击弹出遮罩抽屉', env: '手机端默认且唯一强制 / 极限窄屏可降级' },
  { mode: '📐 侧边图标模式 (Icon Mini)', width: '固定 64px', behavior: '仅 L1 图标 / Hover 浮名称 / Click 弹二级 / 底部"展开"按钮', env: '平板端默认 / 电脑端可手动切换' },
  { mode: '📋 侧边菜单模式 (Full Expanded)', width: '200-400px (可拖动)', behavior: '完整多级树 / 底部"折叠"按钮 / 边缘拖拽改宽', env: '电脑端默认 / 手机平板禁用' },
]

const modePreserveRows = [
  { title: '默认折叠', desc: '首次加载 / 路由跳转 / 刷新时, 非当前激活路由的菜单项默认收起' },
  { title: '精准激活', desc: '当前路径对应菜单项 + 所有父级, 初始化时高亮 + 默认展开' },
  { title: '状态持久化', desc: '用户偏好 (折叠 / 展开) 写入 LocalStorage, 跨设备持久 (受限于屏幕尺寸)' },
]

const responsiveSpacing = [
  { viewport: '📱 手机端 (Mobile)', range: '< 768px',   pad: '4px',  mb: '4px' },
  { viewport: '📱 平板端 (Tablet)', range: '768-1023px', pad: '8px',  mb: '8px' },
  { viewport: '💻 电脑端 (Desktop)', range: '>= 1024px', pad: '16px', mb: '16px' },
]
</script>

<template>
  <div class="app-page spec-page">
    <HeaderSection
      title="二、布局 (Layout)"
      subtitle="所有页面的 layout / 组件嵌套 / 菜单模式 / 响应式间距"
    />

    <!-- §1 统一布局模式 -->
    <TitledSection
      title="1. 统一布局模式"
      description="根据业务场景, 在以下三种基础布局模式中进行选择, 不得混用."
    >
      <RowCard>
        <el-table :data="[
          { mode: '🏢 APP 模式',   structure: 'AppHeader + AppSidebar + AppMain', scene: '复杂的系统主界面, 需要强导航与多级功能管理' },
          { mode: '📦 模块模式',   structure: 'AppSidebar + AppMain',             scene: '特定子系统、控制面板、或二级模块内部' },
          { mode: '📄 单页模式',   structure: 'AppMain (独占)',                    scene: '大屏看板、数据报表、表单向导、全屏编辑器或独立营销页' },
        ]" border>
          <el-table-column label="模式" prop="mode" width="120" />
          <el-table-column label="结构" prop="structure" width="360" />
          <el-table-column label="场景" prop="scene" />
        </el-table>
      </RowCard>
      <RowCard title="模式选择决策树">
        <pre class="tree-block">{{ decisionTree }}</pre>
      </RowCard>
    </TitledSection>

    <!-- §2 核心布局组件与约束 -->
    <TitledSection
      title="2. 核心布局组件与约束"
      description="全站布局组件位置、嵌套关系、样式控制必须严格遵循下表, 杜绝'套娃'视觉."
    >
      <RowCard title="整体布局示意">
        <pre class="diagram-block">{{ asciiDiagram }}</pre>
      </RowCard>

      <!-- §2.1 顶部栏 -->
      <h3 class="sub-h3">2.1 顶部栏 (AppHeader)</h3>
      <ul class="rule-list">
        <li><strong>定位</strong>: 在任何环境 (手机、平板、电脑) 下, 都必须<strong>牢固磁吸在视口顶部</strong>.</li>
        <li><strong>核心样式</strong>: <code>position: sticky; top: 0; z-index: 1000;</code></li>
      </ul>
      <RowCard>
        <pre class="code-block">{{ stickyHeaderScss }}</pre>
      </RowCard>

      <h4 class="sub-h4">2.1.1 全局常量 (不可修改)</h4>
      <p class="muted">🎯 编译时锁定 (<code>as const</code>), 任何主题/品牌/运行时代码均不可覆写. 定义在 <code>src/config/header-tokens.ts</code>.</p>
      <RowCard>
        <el-table :data="headerConstants" border>
          <el-table-column label="常量名" prop="name" width="220" />
          <el-table-column label="取值" prop="value" width="80" align="center" />
          <el-table-column label="约束来源" prop="source" width="120" />
          <el-table-column label="说明" prop="desc" />
        </el-table>
      </RowCard>
      <p class="note">⚠️ z-index 故意写为字面量, 不进 CSS 变量.</p>

      <h4 class="sub-h4">2.1.2 全局变量 (可主题切换, 默认 = 当前 DEMO)</h4>
      <p class="muted">🎯 CSS 变量 (<code>var(--app-header-*)</code>), 默认值 = 当前 DEMO 设计, 品牌 SCSS 可覆写.</p>

      <h5 class="sub-h5">布局尺寸 (容器 / 间距 / 字号)</h5>
      <RowCard>
        <el-table :data="headerLayoutVars" border>
          <el-table-column label="变量名" prop="name" width="280" />
          <el-table-column label="默认值" prop="value" width="120" align="center" />
          <el-table-column label="用途" prop="desc" />
        </el-table>
      </RowCard>

      <h5 class="sub-h5">视觉 (背景 / 边框 / 阴影 / 模糊)</h5>
      <RowCard>
        <el-table :data="headerVisualVars" border>
          <el-table-column label="变量名" prop="name" width="220" />
          <el-table-column label="默认值" prop="value" width="160" align="center" />
          <el-table-column label="用途" prop="desc" width="120" />
          <el-table-column label="品牌覆写" prop="override" />
        </el-table>
      </RowCard>

      <!-- §2.2 侧边栏 -->
      <h3 class="sub-h3">2.2 侧边栏 (AppSidebar)</h3>
      <ul class="rule-list">
        <li>承载系统的主要导航菜单, 需要满足 <a href="#3">§3 "侧边栏菜单三种模式"</a> 详细定义.</li>
        <li>跨端行为: 移动端折叠成抽屉, 平板/电脑端支持 Icon Mini / Full Expanded 两种切换.</li>
      </ul>

      <h4 class="sub-h4">2.2.1 全局常量 (不可修改)</h4>
      <RowCard>
        <el-table :data="sidebarConstants" border>
          <el-table-column label="常量" prop="name" width="220" />
          <el-table-column label="取值" prop="value" width="160" align="center" />
          <el-table-column label="说明" prop="desc" />
        </el-table>
      </RowCard>

      <h4 class="sub-h4">2.2.2 全局变量 (mchuan + light 默认)</h4>
      <RowCard>
        <el-table :data="sidebarVars" border>
          <el-table-column label="变量" prop="name" width="260" />
          <el-table-column label="默认值" prop="value" width="160" align="center" />
          <el-table-column label="用途" prop="desc" />
        </el-table>
      </RowCard>

      <!-- §2.3 工作区 -->
      <h3 class="sub-h3">2.3 工作区 (AppMain)</h3>
      <ul class="rule-list">
        <li><strong>核心内容区</strong>: 工作区是页面功能承载的主体.</li>
        <li><strong>严格的嵌套约束</strong>: 工作区中只能放置功能块 (Section)、工具栏 (ToolBar)、底部栏 (FooterBar)、右侧栏 (RightSidebar) 和布局组件. 工作区内部<strong>不得再出现与工作区完全相同或嵌套的功能区块线框</strong>, 避免"套娃"视觉.</li>
        <li><strong>对齐原则</strong>: 工作区内的所有组件和内容<strong>必须默认靠左 (left) 对齐</strong>, 禁止无故居中对齐.</li>
      </ul>
      <p class="warning">⚠️ <strong>铁律</strong>: Section 内不能套 Section, 工作区内不能再出现"区块类"外框.</p>

      <h4 class="sub-h4">2.3.1 全局变量 (mchuan + light 默认)</h4>
      <RowCard>
        <el-table :data="mainVars" border>
          <el-table-column label="变量" prop="name" width="240" />
          <el-table-column label="默认值" prop="value" width="120" align="center" />
          <el-table-column label="用途" prop="desc" />
        </el-table>
      </RowCard>

      <!-- §2.4 布局组件 -->
      <h3 class="sub-h3">2.4 布局组件 (工作区内)</h3>
      <ul class="rule-list">
        <li><strong>间距归零</strong>: 在工作区内使用的布局组件 (如 el-row / el-col / el-tabs 等), 其 <code>padding</code> 和 <code>margin</code> 必须全部设置为 <code>0</code>.</li>
        <li>若布局组件含有可视化边框或背景 (如卡片样式的 Tabs), 其可视化外框和样式表现必须与功能块 (Section) 保持高度一致.</li>
      </ul>
      <RowCard>
        <pre class="code-block">{{ layoutZeroScss }}</pre>
      </RowCard>

      <!-- §2.5 功能块 -->
      <h3 class="sub-h3">2.5 功能块 (Section)</h3>
      <ul class="rule-list">
        <li><strong>原子性</strong>: 功能块是页面中最小的可视化视觉区块, 包含独立的白色/浅色背景、圆角及阴影.</li>
        <li><strong>嵌套限制</strong>: 功能块<strong>只能</strong>存放在工作区或工作区下的布局组件中, 且<strong>绝对禁止相互嵌套</strong>.</li>
        <li><strong>内部去线框化</strong>: 功能块内部不允许再出现区块类的边框线, 但允许并仅限于使用输入类组件自带的边框.</li>
      </ul>
      <p class="note">📌 输入类型组件 (el-input / el-select / el-textarea / el-button / el-tag / el-switch / el-checkbox / el-radio) 的边框保留, 因为它们是<strong>字段边框</strong>而非<strong>区块边框</strong>.</p>

      <h4 class="sub-h4">2.5.1 全局常量 (不可修改)</h4>
      <RowCard>
        <el-table :data="sectionConstants" border>
          <el-table-column label="常量" prop="name" width="160" />
          <el-table-column label="取值" prop="value" width="320" align="center" />
          <el-table-column label="说明" prop="desc" />
        </el-table>
      </RowCard>

      <h4 class="sub-h4">2.5.2 全局变量 (mchuan + light 默认)</h4>
      <RowCard>
        <el-table :data="sectionVars" border>
          <el-table-column label="变量" prop="name" width="240" />
          <el-table-column label="默认值" prop="value" width="320" align="center">
            <template #default="{ row }">
              <code>{{ row.value }}</code>
            </template>
          </el-table-column>
          <el-table-column label="用途" prop="desc" />
        </el-table>
      </RowCard>

      <!-- §2.6 3 bar -->
      <h3 class="sub-h3">2.6 工具栏 (ToolBar)、底部栏 (FooterBar)、右侧栏 (RightSidebar)</h3>
      <ul class="rule-list">
        <li><strong>数量限制</strong>: 上述组件只能存放在工作区中, 且在同一个工作区内<strong>最多只能各出现一个</strong>.</li>
        <li><strong>磁吸定位</strong>: 工具栏磁吸在工作区顶部 / 底部栏磁吸在工作区底部 / 右侧栏磁吸在工作区右侧.</li>
        <li><strong>右侧栏的响应式适配</strong>: 在<strong>手机端</strong>时, 右侧栏必须收缩隐藏为一个漂浮的图标, 用户点击后, 以抽屉形式从右侧向左展开.</li>
      </ul>
      <RowCard>
        <pre class="code-block">{{ barStickyScss }}</pre>
      </RowCard>

      <h4 class="sub-h4">2.6.1 全局常量 (不可修改)</h4>
      <RowCard>
        <el-table :data="barConstants" border>
          <el-table-column label="常量" prop="name" width="220" />
          <el-table-column label="取值" prop="value" width="100" align="center" />
          <el-table-column label="说明" prop="desc" />
        </el-table>
      </RowCard>

      <h4 class="sub-h4">2.6.2 全局变量 (mchuan + light 默认)</h4>
      <RowCard>
        <el-table :data="barVars" border>
          <el-table-column label="变量" prop="name" width="220" />
          <el-table-column label="默认值" prop="value" width="160" align="center" />
          <el-table-column label="用途" prop="desc" />
        </el-table>
      </RowCard>
    </TitledSection>

    <!-- §3 侧边栏菜单三种模式 -->
    <TitledSection
      title="3. 侧边栏菜单三种模式规范"
      description="为保障跨端体验的一致性与操作便利性, 侧边栏 (AppSidebar) 在不同设备和状态下, 必须在以下三种模式中进行流转."
    >
      <h3 class="sub-h3">3.1 模式定义</h3>
      <RowCard>
        <el-table :data="modeRows" border>
          <el-table-column label="菜单模式" prop="mode" width="240" />
          <el-table-column label="视觉宽度" prop="width" width="140" />
          <el-table-column label="交互行为" prop="behavior" />
          <el-table-column label="适用环境与默认行为" prop="env" width="180" />
        </el-table>
      </RowCard>

      <h3 class="sub-h3">模式流转规则</h3>
      <RowCard>
        <pre class="tree-block">{{ modeFlow }}</pre>
      </RowCard>

      <h3 class="sub-h3">3.2 导航与状态保持逻辑</h3>
      <RowCard>
        <ul class="rule-list">
          <li v-for="r in modePreserveRows" :key="r.title">
            <strong>{{ r.title }}</strong>: {{ r.desc }}
          </li>
        </ul>
      </RowCard>
      <RowCard>
        <pre class="code-block">{{ sidebarStateTs }}</pre>
      </RowCard>
    </TitledSection>

    <!-- §4 响应式间距与栅格规范 -->
    <TitledSection
      title="4. 响应式间距与栅格规范"
      description="工作区内的间距必须按视口断点严格遵循 4 / 8 / 16px 三档响应式数值, 保证跨端视觉呼吸一致."
    >
      <h3 class="sub-h3">4.1 工作区弹性间距 (Responsive Spacing)</h3>
      <RowCard>
        <pre class="code-block">{{ responsiveScss }}</pre>
      </RowCard>

      <h3 class="sub-h3">间距对照表</h3>
      <RowCard>
        <el-table :data="responsiveSpacing" border>
          <el-table-column label="视口" prop="viewport" width="200" />
          <el-table-column label="宽度" prop="range" width="160" align="center" />
          <el-table-column label="padding" prop="pad" width="120" align="center" />
          <el-table-column label="margin-bottom" prop="mb" width="120" align="center" />
        </el-table>
      </RowCard>
      <p class="note">📌 2026-06-08 更新: 不再强制手机端单列化. 多 Section 可同行并排 (<code>auto-fit</code>), 用户可保留横向卡片墙.</p>
    </TitledSection>

    <p class="closing-note">
      <strong>本布局规范是 1总则.md §1.4 响应式设计原则的具体实施.</strong>
      任何页面布局都应先选定 §1 的 3 种基础模式, 再按 §2 嵌套规则组装, 最后用 §4 响应式间距统一视觉呼吸.
    </p>
  </div>
</template>

<style scoped>
.spec-page {
  max-width: 1200px;
}
.sub-h3 {
  margin: 16px 0 8px;
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text);
}
.sub-h4 {
  margin: 12px 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
}
.sub-h5 {
  margin: 8px 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text-secondary);
}
.rule-list {
  margin: 0 0 8px;
  padding-left: 20px;
  font-size: 13px;
  color: var(--app-text);
  line-height: 1.7;
}
.rule-list li {
  margin-bottom: 4px;
}
.note {
  margin: 8px 0;
  padding: 8px 12px;
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary);
  border-radius: 4px;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.6;
}
.warning {
  margin: 8px 0;
  padding: 8px 12px;
  background: var(--el-color-warning-light-9);
  border-left: 3px solid var(--el-color-warning);
  border-radius: 4px;
  font-size: 13px;
  color: var(--app-text);
  line-height: 1.6;
}
.muted {
  margin: 6px 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}
.code-block, .diagram-block, .tree-block {
  margin: 0;
  padding: 12px 16px;
  background: var(--app-bg-muted);
  border-radius: var(--app-radius-sm);
  font-family: 'Fira Code', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text);
  white-space: pre;
  overflow-x: auto;
}
.diagram-block {
  white-space: pre;
}
.tree-block {
  white-space: pre;
}
.closing-note {
  margin-top: 24px;
  padding: 16px;
  background: var(--app-bg-muted);
  border-radius: var(--app-radius-md);
  font-size: 13px;
  color: var(--app-text-secondary);
  line-height: 1.7;
}
</style>