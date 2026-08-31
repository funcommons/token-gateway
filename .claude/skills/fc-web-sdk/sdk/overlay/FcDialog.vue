<script setup lang="ts">defineOptions({ name: 'FcDialog' })
/**
 * FcDialog — 统一的 el-dialog 包装 (SDK).
 *
 * 用法 A (推荐, v-model 双向绑定):
 *   <FcDialog v-model:open="open" title="确认" @confirm="onConfirm">
 *     <p>内容</p>
 *     <template #footer>
 *       <el-button @click="open = false">取消</el-button>
 *       <el-button type="primary" @click="onConfirm">确认</el-button>
 *     </template>
 *   </FcDialog>
 *
 * 用法 B (active + @toggle, 多 dialog 互斥):
 *   <FcDialog :active="current === 'edit'" @toggle="..." />
 *
 * 内置能力:
 * - v-model:open 与 active+@toggle 双模式, 默认值收敛
 * - 打开时自动锁 body 滚动 (useBodyScrollLock)
 * - 默认居中 (alignCenter=true), 可关
 * - 默认点击遮罩关闭, 可关
 * - 自定义 header / footer slot
 * - draggable (头部当拖动手柄) + resizable (右下角手柄) 可选
 * - fullscreen / appendToBody / destroyOnClose 透传 ElDialog
 */
import { ref, computed, watch } from 'vue'
import { useBodyScrollLock } from '@/composables'

interface Props {
  /** 方式 A: v-model:open 双向绑定开关状态 */
  open?: boolean
  /** 方式 B: 单向 active prop (配合 @toggle 事件) */
  active?: boolean
  /** 标题 (withHeader=true 时显示) */
  title?: string
  /** 弹窗宽度 (number=px, string=CSS 含百分比). 默认 480. */
  width?: string | number
  /** 弹窗高度 (number=px, string=CSS). 不传=auto. 设了之后 dialog 整体固定高度, body 用 flex 撑满. */
  height?: number | string
  /** 是否居中, 默认 true */
  alignCenter?: boolean
  /** 点击遮罩是否关闭, 默认 true */
  closeOnClickModal?: boolean
  /** 是否显示关闭按钮, 默认 true */
  showClose?: boolean
  /** 是否显示 header (title 栏), 默认 true. 内容自带标题时设 false. */
  withHeader?: boolean
  /** 顶层 dialog class */
  dialogClass?: string
  /** 顶层 body class */
  bodyClass?: string
  /** 是否可通过 ESC 关闭, 默认 true */
  closeOnPressEscape?: boolean
  /** 头部当拖动手柄, 默认 true */
  draggable?: boolean
  /** 右下角调大小手柄, 默认 false */
  resizable?: boolean
  /** resizable 最小宽度 (px). 默认 360 */
  minWidth?: number
  /** resizable 最小高度 (px). 默认 240 */
  minHeight?: number
  /** 手机端全屏 (透传 ElDialog fullscreen). 默认 false */
  fullscreen?: boolean
  /** 透传 ElDialog append-to-body. 默认 false */
  appendToBody?: boolean
  /** 透传 ElDialog destroy-on-close. 默认 false */
  destroyOnClose?: boolean
  /** 透传 ElDialog modal-class (用于自定义遮罩). 默认空 */
  modalClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  open: false,
  active: undefined,
  title: '',
  width: 480,
  height: undefined,
  alignCenter: true,
  closeOnClickModal: true,
  showClose: true,
  withHeader: true,
  dialogClass: '',
  bodyClass: '',
  closeOnPressEscape: true,
  draggable: true,
  resizable: false,
  minWidth: 360,
  minHeight: 240,
  fullscreen: false,
  appendToBody: false,
  destroyOnClose: false,
  modalClass: '',
})

const emit = defineEmits<{
  'update:open': [boolean]
  /** 方式 B: 状态变化时触发 */
  toggle: [boolean]
  /** 关闭前 (用户点 X / 遮罩 / ESC), 用于阻止关闭 (return false) */
  'before-close': []
  /** 关闭后 */
  close: []
  /** resizable=true 时, 用户拖动手柄后触发 */
  resize: [width: number, height: number | '']
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
function handleClosed() { emit('close') }

useBodyScrollLock(computed(() => innerOpen.value))

const dialogClassFull = computed(() => ['fc-dialog', props.dialogClass].filter(Boolean).join(' '))

// ===== resizable: 跟踪 width/height, fullscreen 时禁用 =====
const isPercentWidth = typeof props.width === 'string' && props.width.endsWith('%')
const innerWidth = ref<number>(
  typeof props.width === 'number'
    ? props.width
    : (isPercentWidth ? 520 : (parseInt(String(props.width)) || 520)),
)
const innerHeight = ref<number | ''>('')

const dialogWidth = computed(() => {
  if (props.fullscreen) return undefined
  if (isPercentWidth) return props.width as string
  return innerWidth.value + 'px'
})

// height: resizable 手柄 > prop > auto
const dialogHeightStyle = computed<string | undefined>(() => {
  if (props.fullscreen) return undefined
  if (innerHeight.value) return innerHeight.value + 'px'
  if (props.height !== undefined) {
    return typeof props.height === 'number' ? props.height + 'px' : props.height
  }
  return undefined
})

function startResize(e: PointerEvent) {
  e.preventDefault()
  const startX = e.clientX
  const startY = e.clientY
  const startW = innerWidth.value
  const startH = innerHeight.value || 0
  function onMove(ev: PointerEvent) {
    const newW = Math.max(props.minWidth, startW + (ev.clientX - startX))
    innerWidth.value = newW
    if (startH > 0) {
      innerHeight.value = Math.max(props.minHeight, startH + (ev.clientY - startY))
    }
    emit('resize', innerWidth.value, innerHeight.value)
  }
  function onUp() {
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
  }
  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}
</script>

<template>
  <el-dialog
    v-model="innerOpen"
    :title="withHeader ? title : undefined"
    :width="dialogWidth"
    :align-center="alignCenter"
    :close-on-click-modal="closeOnClickModal"
    :close-on-press-escape="closeOnPressEscape"
    :show-close="showClose"
    :draggable="draggable"
    :fullscreen="fullscreen"
    :append-to-body="appendToBody"
    :destroy-on-close="destroyOnClose"
    :modal-class="modalClass"
    :class="dialogClassFull"
    :body-class="bodyClass"
    :style="{ height: dialogHeightStyle }"
    :before-close="() => { emit('before-close'); handleClose() }"
    @closed="handleClosed"
  >
    <template v-if="$slots.header" #header>
      <slot name="header" :close="handleClose" />
    </template>
    <slot :close="handleClose" />
    <div
      v-if="resizable && !fullscreen"
      class="fc-dialog__resize"
      :title="title ? `Resize ${title}` : 'Resize'"
      @pointerdown="startResize"
    />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" :close="handleClose" />
    </template>
  </el-dialog>
</template>

<style>
/* 非 scoped: el-dialog teleport 到 body 后 scoped 失效, 用全局选择器 */
.fc-dialog.el-dialog {
  border-radius: var(--brand-radius-lg, var(--radius-lg));
  overflow: hidden;
}

.fc-dialog .el-dialog__header {
  padding: var(--space-md) var(--space-lg);
  margin: 0;
  border-bottom: 1px solid var(--app-separator);
}

.fc-dialog .el-dialog__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--app-text-primary);
}

.fc-dialog .el-dialog__body {
  padding: var(--space-lg);
  color: var(--app-text-secondary);
}

.fc-dialog .el-dialog__footer {
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--app-separator);
}

/* 调大小手柄: 12px 视觉 + 移动触摸区 */
.fc-dialog__resize {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 12px;
  height: 12px;
  cursor: nwse-resize;
  background-image: linear-gradient(135deg,
    transparent 30%, var(--app-text-tertiary, #999) 30%, var(--app-text-tertiary, #999) 38%, transparent 38%,
    transparent 55%, var(--app-text-tertiary, #999) 55%, var(--app-text-tertiary, #999) 63%, transparent 63%,
    transparent 80%, var(--app-text-tertiary, #999) 80%, var(--app-text-tertiary, #999) 88%, transparent 88%);
  opacity: 0.5;
  transition: opacity 0.15s;
  touch-action: none;
  z-index: 1;
}
.fc-dialog__resize::before {
  content: '';
  position: absolute;
  inset: -16px;
  background: transparent;
}
.fc-dialog__resize:hover { opacity: 0.9; }
</style>
