<script setup lang="ts">defineOptions({ name: 'FcDrawer' })
/**
 * FcDrawer — 统一的 el-drawer 包装.
 *
 * 用法 A (推荐, v-model 双向绑定):
 *   <FcDrawer v-model:open="open" title="设置" direction="rtl" size="360px">
 *     <SettingsPanel />
 *   </FcDrawer>
 *
 * 用法 B (active + @toggle, 多 drawer 互斥):
 *   <FcDrawer :active="current === 'a'" @toggle="..." />
 *
 * 内置能力:
 * - v-model:open 与 active+@toggle 双模式, 默认值收敛
 * - 抽屉贴屏幕边那一侧自动去圆角 (与 FcPopover 行为一致)
 * - 打开时 el-drawer 自带 lock-scroll
 * - 默认带 header + 关闭按钮, 可关
 */
import { ref, computed, watch } from 'vue'

type DrawerDirection = 'rtl' | 'ltr' | 'ttb' | 'btt'

interface Props {
  /** 方式 A: v-model:open 双向绑定开关状态 */
  open?: boolean
  /** 方式 B: 单向 active prop (配合 @toggle 事件) */
  active?: boolean
  /** 标题 (withHeader=true 时显示) */
  title?: string
  /** 抽屉滑入方向, 默认 'rtl' (右侧, 适合设置/详情) */
  direction?: DrawerDirection
  /** 抽屉尺寸, 横向时=宽度, 纵向时=高度. 默认 '360px'. */
  size?: string | number
  /** 是否显示 header, 默认 true */
  withHeader?: boolean
  /** 是否显示关闭按钮, 默认 true */
  showClose?: boolean
  /** 点击遮罩是否关闭, 默认 true */
  closeOnClickModal?: boolean
  /** 是否可通过 ESC 关闭, 默认 true */
  closeOnPressEscape?: boolean
  /** 顶层 drawer class */
  drawerClass?: string
  /** 顶层 body class */
  bodyClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  open: false,
  active: undefined,
  title: '',
  direction: 'rtl',
  size: '360px',
  withHeader: true,
  showClose: true,
  closeOnClickModal: true,
  closeOnPressEscape: true,
  drawerClass: '',
  bodyClass: '',
})

const emit = defineEmits<{
  'update:open': [boolean]
  /** 方式 B: 状态变化时触发 */
  toggle: [boolean]
  /** 关闭前 (用户点 X / 遮罩 / ESC) */
  'before-close': []
}>()

const innerOpen = ref(props.active ?? props.open)

watch(() => props.active, v => { if (v !== undefined) innerOpen.value = v })
watch(() => props.open, v => { if (props.active === undefined) innerOpen.value = v })

watch(innerOpen, v => {
  if (v !== props.open) emit('update:open', v)
  if (v !== props.active) emit('toggle', v)
})

function setOpen(v: boolean) { innerOpen.value = v }
function handleClose() { setOpen(false) }

const drawerClassFull = computed(() => ['fc-drawer', props.drawerClass].filter(Boolean).join(' '))
</script>

<template>
  <el-drawer
    v-model="innerOpen"
    :title="withHeader ? title : undefined"
    :with-header="withHeader"
    :show-close="showClose"
    :direction="direction"
    :size="size"
    :close-on-click-modal="closeOnClickModal"
    :close-on-press-escape="closeOnPressEscape"
    :class="drawerClassFull"
    :body-class="bodyClass"
    :before-close="() => { emit('before-close'); handleClose() }"
  >
    <template v-if="$slots.header" #header>
      <slot name="header" :close="handleClose" />
    </template>
    <slot :close="handleClose" />
  </el-drawer>
</template>

<style>
/* 非 scoped: el-drawer teleport 到 body 后 scoped 失效, 用全局选择器 */
.fc-drawer.el-drawer {
  border-radius: var(--brand-radius-lg, var(--radius-lg));
}

.fc-drawer.el-drawer.rtl {
  border-top-right-radius: 0;
  border-bottom-right-radius: 0;
}

.fc-drawer.el-drawer.ltr {
  border-top-left-radius: 0;
  border-bottom-left-radius: 0;
}

.fc-drawer.el-drawer.ttb {
  border-top-left-radius: 0;
  border-top-right-radius: 0;
}

.fc-drawer.el-drawer.btt {
  border-bottom-left-radius: 0;
  border-bottom-right-radius: 0;
}

.fc-drawer .el-drawer__header {
  padding: var(--space-md) var(--space-lg);
  margin: 0;
  border-bottom: 1px solid var(--app-separator);
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.fc-drawer .el-drawer__body {
  padding: 0;
}

.fc-drawer .el-drawer__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text-primary);
}
</style>
