<script setup lang="ts">
defineOptions({ name: 'FcSidebar' })
/**
 * FcSidebar - 视觉壳 (SDK).
 *
 * 5 个 slot: header / default (nav 内容) / footer / toggle / resize-handle.
 * 拖拽逻辑保留在 SDK 内 (纯 UI, 宽度变化经 v-model 抛给 host 写 store).
 *
 * 折叠态 - 三种用法:
 *   1. 完全受控 (推荐)  : <FcSidebar v-model:collapsed="x" />  v-model 接管
 *   2. 非受控 + 默认值 : <FcSidebar :default-collapsed="true" /> 内部 state, mount 时初始化
 *   3. 强制覆写       : <FcSidebar :force-collapsed="isMobile" />  viewport 变化时强制折叠
 *
 * 用法:
 *   <FcSidebar>
 *     <FcSidebarNav :items="navItems" :active-path="route.path" @select="router.push" />
 *   </FcSidebar>
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useResponsive } from '@/composables'

interface Props {
  /** 受控: 外部 v-model 传入. 不传则内部自管. */
  collapsed?: boolean
  /** 非受控模式初值. 仅在 collapsed 没传时生效. */
  defaultCollapsed?: boolean
  width?: number
  defaultWidth?: number
  minWidth?: number
  maxWidth?: number
  collapsedWidth?: number
  /** viewport 级强制覆写 (如移动端). 优先级最高. */
  forceCollapsed?: boolean
  enableDrag?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  collapsed: undefined,
  defaultCollapsed: false,
  width: 240,
  defaultWidth: 240,
  minWidth: 200,
  maxWidth: 400,
  collapsedWidth: 64,
  forceCollapsed: undefined,
  enableDrag: true,
})

const emit = defineEmits<{
  select: [path: string]
  'reset-width': []
  'toggle-sidebar': []
  'update:collapsed': [v: boolean]
  'update:width': [v: number]
}>()

const { isMobile } = useResponsive()

// 内部折叠态 - mount 时读 defaultCollapsed, 受控时跟随 props.collapsed
const innerCollapsed = ref<boolean>(props.collapsed ?? props.defaultCollapsed)

// 外部 props.collapsed 变化时同步内部 (受控模式)
watch(() => props.collapsed, (v) => {
  if (v !== undefined) innerCollapsed.value = v
})

const effectiveCollapsed = computed(() => {
  if (props.forceCollapsed !== undefined) return props.forceCollapsed
  return innerCollapsed.value
})

const sidebarWidth = computed(() => {
  if (effectiveCollapsed.value) return props.collapsedWidth
  return props.width || props.defaultWidth
})

const sidebarStyle = computed(() => ({
  width: `${sidebarWidth.value}px`,
}))

function toggle() {
  if (props.forceCollapsed !== undefined) return  // 强制态下不允许 toggle
  const next = !innerCollapsed.value
  innerCollapsed.value = next
  emit('update:collapsed', next)
  emit('toggle-sidebar')
}

// ---- Drag handle ----
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

// expose 给外部访问内部状态 (非受控模式时业务想读取)
defineExpose({
  toggle,
  innerCollapsed,
})
</script>

<template>
  <aside
    class="fc-sidebar"
    :style="sidebarStyle"
    :class="{ 'is-dragging': isDragging, 'is-collapsed': effectiveCollapsed }"
  >
    <slot name="header" />

    <div class="fc-sidebar__nav">
      <slot />
    </div>

    <slot name="footer" />

    <button
      v-if="!isMobile && props.forceCollapsed === undefined"
      type="button"
      class="fc-sidebar__toggle"
      :title="effectiveCollapsed ? 'Expand' : 'Collapse'"
      @click="toggle"
    >
      <i :class="effectiveCollapsed ? 'ri-menu-unfold-line' : 'ri-menu-fold-line'" />
    </button>

    <div
      v-if="!isMobile && props.enableDrag && !effectiveCollapsed"
      class="fc-sidebar__resize"
      title="Drag to resize / double-click to reset"
      @mousedown="onDragStart"
      @dblclick="onDoubleClick"
    />
  </aside>
</template>

<style scoped lang="scss" src="./FcSidebar.styles.scss"></style>