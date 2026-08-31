<script setup lang="ts">
// AppSidebar — 纯视觉壳 (SDK).
// 5 个 slot: header / default (nav 内容) / footer / toggle / resize-handle.
// 拖拽逻辑保留在 SDK 内 (纯 UI, 宽度变化经 v-model 抛给 host 写 store).
// 折叠态 props 接受 collapsed + forceCollapsed (用于 App.vue 的 per-viewport 覆写).
//
// SDK 不绑:
//   • useRouter / useRoute  (select 走 emit, host 决定 router.push)
//   • usePreferenceStore     (v-model 状态由 host 接 store)
//   • routes 导入           (nav 内容由 host 写 slot)
//   • 硬编码 badgeMap / groups (host 业务侧在 App.vue 自己塞)
import { computed, onBeforeUnmount, ref } from 'vue'
import { useResponsive } from '@/composables/useResponsive'

interface Props {
  /** 折叠态, v-model:collapsed 双向绑定 */
  collapsed?: boolean
  /** 展开宽度 (px), v-model:width 双向绑定 */
  width?: number
  defaultWidth?: number
  minWidth?: number
  maxWidth?: number
  collapsedWidth?: number
  /** 桌面 / 平板的 per-viewport 折叠态覆写 (App.vue 传) */
  forceCollapsed?: boolean
  /** 是否启用拖拽手柄 (移动端 drawer 关掉) */
  enableDrag?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  collapsed: false,
  width: 240,
  defaultWidth: 240,
  minWidth: 200,
  maxWidth: 400,
  collapsedWidth: 64,
  forceCollapsed: undefined,
  enableDrag: true,
})

const emit = defineEmits<{
  /** 用户点 nav 项, host 决定 router.push */
  select: [path: string]
  /** 双击拖拽手柄, host 决定重置到 defaultWidth */
  'reset-width': []
  /** 折叠按钮被点, host 决定折叠态切换 / 抽屉打开 */
  'toggle-sidebar': []
  'update:collapsed': [v: boolean]
  'update:width': [v: number]
}>()

const { isMobile } = useResponsive()

const effectiveCollapsed = computed(() => {
  if (props.forceCollapsed !== undefined) return props.forceCollapsed
  return props.collapsed
})

const sidebarWidth = computed(() => {
  if (effectiveCollapsed.value) return props.collapsedWidth
  return props.width || props.defaultWidth
})

const sidebarStyle = computed(() => ({
  width: `${sidebarWidth.value}px`,
}))

function onSelect(index: string) {
  emit('select', index)
}

// ---- Drag handle for resizing ----
const isDragging = ref(false)
const dragStartX = ref(0)
const dragStartWidth = ref(0)

function onDragStart(e: MouseEvent) {
  if (effectiveCollapsed.value) return
  isDragging.value = true
  dragStartX.value = e.clientX
  dragStartWidth.value = sidebarWidth.value
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}

function onDragMove(e: MouseEvent) {
  if (!isDragging.value) return
  const delta = e.clientX - dragStartX.value
  const next = Math.max(props.minWidth, Math.min(props.maxWidth, dragStartWidth.value + delta))
  emit('update:width', next)
}

function onDragEnd() {
  isDragging.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

function onDoubleClick() {
  emit('reset-width')
  emit('update:width', props.defaultWidth)
}

onBeforeUnmount(() => {
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
})
</script>

<template>
  <aside
    class="app-sidebar"
    :style="sidebarStyle"
    :class="{ 'is-dragging': isDragging, 'is-collapsed': effectiveCollapsed }"
  >
    <slot name="header" />

    <div class="app-sidebar__nav">
      <slot @select="onSelect" />
    </div>

    <slot name="footer" />

    <button
      v-if="!isMobile"
      type="button"
      class="app-sidebar__toggle"
      :title="effectiveCollapsed ? 'Expand' : 'Collapse'"
      @click="emit('toggle-sidebar')"
    >
      <i :class="effectiveCollapsed ? 'ri-menu-unfold-line' : 'ri-menu-fold-line'" />
    </button>

    <div
      v-if="!isMobile && props.enableDrag && !effectiveCollapsed"
      class="app-sidebar__resize"
      title="Drag to resize / double-click to reset"
      @mousedown="onDragStart"
      @dblclick="onDoubleClick"
    />
  </aside>
</template>

<style scoped>
.app-sidebar {
  background: var(--el-bg-color);
  border-right: 1px solid var(--el-border-color-extra-light);
  height: calc(100vh - 56px);
  overflow: hidden;
  position: sticky;
  top: 56px;
  transition: width 0.2s ease;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-width: 64px;
  max-width: 400px;
}

.app-sidebar__nav {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: rgba(0, 0, 0, 0.18) transparent;
  padding: 6px 0 56px;
}
.app-sidebar__nav :deep(.el-menu .el-menu) {
  padding: 6px 0 0;
}

/* scrollbar */
.app-sidebar__nav :deep(.el-menu::-webkit-scrollbar) { width: 6px; height: 6px; }
.app-sidebar__nav :deep(.el-menu::-webkit-scrollbar-track) { background: transparent; }
.app-sidebar__nav :deep(.el-menu::-webkit-scrollbar-thumb) {
  background-color: rgba(0, 0, 0, 0.18);
  border-radius: 999px;
  border: 2px solid transparent;
  background-clip: padding-box;
}
.app-sidebar__nav :deep(.el-menu::-webkit-scrollbar-thumb:hover) {
  background-color: rgba(0, 0, 0, 0.32);
  background-clip: padding-box;
}

/* ============================================================
   菜单项 — 最小化自定义, 让 EP 原生机制处理:
   • EP 自动用 --el-menu-level 决定 L2/L3/L4 缩进
   • EP 自动在折叠态创建 cascading popper (el-menu--popup-container)
   • EP 自动用 el-icon 包裹 + text-align: center 居中图标
   • 我们只覆写: 圆角 (项目规范), active 状态 (rounded pill)
   ============================================================ */

/* 菜单项 + 子菜单标题: 项目圆角 (8px L1, 6px sub) + 间距 */
.app-sidebar__nav :deep(.el-menu-item),
.app-sidebar__nav :deep(.el-sub-menu__title) {
  border-radius: 8px;
  margin: 2px 8px;
  height: 40px;
  line-height: 40px;
}
.app-sidebar__nav :deep(.el-menu-item.is-active) {
  font-weight: 600;
}
.app-sidebar__nav :deep(.el-sub-menu .el-menu-item) {
  border-radius: 6px;
  height: 36px;
  line-height: 36px;
}

/* 折叠态 (icon-only, 64px wide).
   EP 原生: collapse=true 时, L1 显示 icon only, hover sub-menu 触发
   el-menu--popup-container 级联. 我们只加圆角覆盖 + 隐藏自定义 label +
   把整行 flex 居中让 icon 在 64px 宽里水平居中. */
.app-sidebar.is-collapsed :deep(.el-menu-item),
.app-sidebar.is-collapsed :deep(.el-sub-menu__title) {
  border-radius: 0;
  margin: 2px 0;
  padding-left: 0;
  padding-right: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.app-sidebar.is-collapsed :deep(.el-sub-menu__title .el-icon),
.app-sidebar.is-collapsed :deep(.el-menu-item .el-icon) {
  margin-right: 0;
}
/* 隐藏 host 业务侧塞的 label span (不用 display:none, 避免破坏 EP 测量) */
.app-sidebar.is-collapsed :deep(.app-menu-item__label) {
  max-width: 0;
  min-width: 0;
  width: 0;
  flex-shrink: 0;
  height: 0;
  visibility: hidden;
  overflow: hidden;
  padding: 0;
  margin: 0;
}
/* 折叠态 inline 子菜单 — 不再强制隐藏.
   EP 在 collapse=true 时会自己用 popper 接管 L1+ 渲染, inline .el-menu 自然 display:none.
   之前用 visibility:hidden 强制收 0 宽高, 会让 EP 的嵌套 popper 找不到子节点, 阻断 L2→L3 级联.
   跟 ldx2t-web-framework 一致: 不动 inline 子菜单, 完全交给 EP 内部 hover 计时器. */

/* Drag handle */
.app-sidebar__resize {
  position: absolute;
  top: 0;
  right: 0;
  width: 4px;
  height: calc(100% - 48px);
  cursor: col-resize;
  background: transparent;
  transition: background 0.15s;
  z-index: 3;
}
.app-sidebar__resize:hover,
.app-sidebar.is-dragging .app-sidebar__resize {
  background: var(--app-primary);
}
.app-sidebar.is-dragging {
  transition: none;
}

/* Toggle button */
.app-sidebar__toggle {
  position: absolute;
  bottom: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--app-text-secondary);
  border-radius: 8px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  transition: background 0.15s, color 0.15s;
  z-index: 4;
}
.app-sidebar__toggle:hover,
.app-sidebar__toggle:active {
  background: transparent;
  color: var(--app-primary);
}
.app-sidebar.is-collapsed .app-sidebar__toggle {
  right: 50%;
  transform: translateX(50%);
  bottom: 12px;
}

/* Mobile: fill the drawer */
@media (max-width: 768px) {
  .app-sidebar {
    width: 100% !important;
    height: 100% !important;
    position: static;
    top: auto;
  }
  .app-sidebar__resize,
  .app-sidebar__toggle {
    display: none;
  }
}

/* ============================================================
   折叠态弹层 (.el-menu--popup-container) — 项目样式规范.
   放非 scoped 块因为 popper teleport 到 body, 不在 AppSidebar 的 scoped 范围.
   ============================================================ */
</style>

<style>
/* 折叠态弹层 — 跟 work-section 视觉一致 (background / radius / border / shadow).
   popper-class="app-sidebar-popper" 同时打到外层 .el-popper.el-tooltip wrapper 和
   内层 .el-menu--popup-container; 边框 + 阴影统一放外层, 内层只填 padding + background,
   避免内外两套样式叠加造成 L1/L2/L3 视觉不一致.

   注意: _ep-overrides.scss 里有一个全局 !important 规则在 .el-popper / .el-popper.is-light
   上强制 border-color: var(--app-border-light) (alpha 0.7, 比 work-section 的
   --el-border-color-extra-light (alpha 0.15) 重). 因为这条全局规则同样 !important,
   为了 sidebar popper 能用自己的轻边框, 这里把 app-sidebar-popper 类名写两次
   把特异性拉到 (0,0,3,0) 压过全局规则的 (0,0,2,0). */
.el-popper.app-sidebar-popper.app-sidebar-popper {
  background-color: var(--el-bg-color) !important;
  border: 1px solid var(--el-border-color-extra-light) !important;
  border-radius: var(--app-radius-card) !important;
  box-shadow: var(--app-shadow-md) !important;
  /* 不用 overflow: hidden — 嵌套子菜单 (L2/L3 popover) 会被 teleport 到 L1 popover 的
     DOM 里, overflow 裁剪会把它们吃掉, 阻断 cascading popper. */
}
.el-menu--popup-container.app-sidebar-popper {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  border-radius: 0 !important;
}
.el-menu--popup-container.app-sidebar-popper .el-menu {
  padding: 8px !important;
  background-color: var(--el-bg-color) !important;
  border: none !important;
  /* 规格 6: 暗色模式 hover 文字色 — 弹层里 el-menu 不在 .app-sidebar 子树内,
     拿不到 _mixins.scss:166 那一行 --el-menu-hover-bg-color = brand-sidebar-hover-bg
     的覆写, 会落到 EP 默认 var(--el-color-primary-light-9) = 10% primary + white,
     暗色模式下变成浅蓝 (#e9effd) 跟 var(--app-text) #e8e8e8 撞色看不见.
     在弹层 .el-menu 上重挂这两个 token, 跟 sidebar 行 hover 行为一致. */
  --el-menu-hover-bg-color: var(--brand-sidebar-hover-bg);
  --el-menu-hover-text-color: var(--brand-sidebar-text-main);
  --el-menu-text-color: var(--brand-sidebar-text-main);
}
.el-menu--popup-container.app-sidebar-popper .el-menu-item {
  border-radius: 6px;
  margin: 2px 0;
  height: 36px;
  line-height: 36px;
  min-width: 180px;
}
</style>
