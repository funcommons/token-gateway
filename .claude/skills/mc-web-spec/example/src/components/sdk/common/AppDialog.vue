<script setup lang="ts">
// AppDialog — 拖动 + 可调大小 + 复用 WorkSection 视觉壳 + 移动端触摸友好的 ElDialog 包装 (SDK).
//
// 行为:
//   • draggable=true  → 头部当拖动手柄, 鼠标变 move 光标
//   • resizable=true  → 右下角 12px 斜线手柄, pointerdown 跟踪 width
//                      移动端 44px 隐形触摸区 (Apple HIG), touch-action: none
//   • mobile 关闭按钮 → 视觉 28px, 触摸 44px
//   • 背景高斯模糊 → modal-class="app-dialog-mask" 触发
//   • 视觉壳 → border-radius / border / shadow / padding 全部对齐
//             WorkSection (--app-radius-card, --app-block-pad, ...)
import { ref } from 'vue'
import { ElDialog } from 'element-plus'

interface Props {
  visible: boolean
  title?: string
  /** 初始宽度 (px), 后续会被可调大小逻辑覆盖 */
  width?: number | string
  draggable?: boolean
  resizable?: boolean
  minWidth?: number
  minHeight?: number
  alignCenter?: boolean
  closeOnClickModal?: boolean
  modalClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  width: 520,
  draggable: true,
  resizable: true,
  minWidth: 360,
  minHeight: 240,
  alignCenter: true,
  closeOnClickModal: false,
  modalClass: 'app-dialog-mask',
})

const emit = defineEmits<{
  'update:visible': [val: boolean]
  'resize': [width: number, height: number | '']
}>()

const innerWidth = ref<number>(
  typeof props.width === 'number' ? props.width : parseInt(String(props.width)) || 520,
)
const innerHeight = ref<number | ''>('')

function onUpdateVisible(v: boolean) {
  emit('update:visible', v)
}

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
  <ElDialog
    :model-value="visible"
    :title="title"
    :width="innerWidth + 'px'"
    :style="{ height: innerHeight ? innerHeight + 'px' : undefined }"
    :draggable="draggable"
    :align-center="alignCenter"
    :close-on-click-modal="closeOnClickModal"
    :modal-class="modalClass"
    @update:model-value="onUpdateVisible"
  >
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
    <div
      v-if="resizable"
      class="app-dialog__resize"
      :title="title ? `Resize ${title}` : 'Resize'"
      @pointerdown="startResize"
    />
  </ElDialog>
</template>

<style scoped>
/* 背景高斯模糊 */
:deep(.app-dialog-mask) {
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  background: rgba(0, 0, 0, 0.25);
}
/* 视觉壳对齐 WorkSection */
:deep(.app-dialog-mask .el-dialog) {
  border-radius: var(--app-radius-card);
  border: 1px solid var(--el-border-color-extra-light);
  box-shadow: var(--app-shadow-md), 0 16px 48px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}
:deep(.app-dialog-mask .el-dialog__header) {
  padding: var(--app-block-pad) calc(var(--app-block-pad) * 1.25);
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-extra-light);
  margin-bottom: 0;
  cursor: move;
}
:deep(.app-dialog-mask .el-dialog__title) {
  font-weight: 600;
  font-size: 15px;
  color: var(--app-text);
}
:deep(.app-dialog-mask .el-dialog__body) {
  padding: var(--app-block-pad);
  color: var(--app-text);
}
:deep(.app-dialog-mask .el-dialog__footer) {
  padding: var(--app-block-pad) calc(var(--app-block-pad) * 1.25);
  border-top: 1px solid var(--el-border-color-extra-light);
  background: var(--app-bg-muted);
}
:deep(.app-dialog-mask .el-dialog) { user-select: none; }

/* 调大小手柄: 12px 视觉 + 44px 移动触摸区 */
.app-dialog__resize {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 12px;
  height: 12px;
  cursor: nwse-resize;
  background-image: linear-gradient(135deg,
    transparent 30%, var(--app-text-tertiary) 30%, var(--app-text-tertiary) 38%, transparent 38%,
    transparent 55%, var(--app-text-tertiary) 55%, var(--app-text-tertiary) 63%, transparent 63%,
    transparent 80%, var(--app-text-tertiary) 80%, var(--app-text-tertiary) 88%, transparent 88%);
  opacity: 0.5;
  transition: opacity 0.15s;
  touch-action: none;
  z-index: 1;
}
.app-dialog__resize::before {
  content: '';
  position: absolute;
  inset: -16px;
  background: transparent;
}
.app-dialog__resize:hover { opacity: 0.9; }
.app-dialog__resize:active { cursor: nwse-resize; }

/* 关闭 × 按钮: 视觉 28px, 移动端触摸 44px */
:deep(.app-dialog-mask .el-dialog__close) {
  width: 28px;
  height: 28px;
  font-size: 18px;
}
:deep(.app-dialog-mask .el-dialog__headerbtn) {
  padding: 0;
  width: 44px;
  height: 44px;
}
@media (min-width: 768px) {
  :deep(.app-dialog-mask .el-dialog__headerbtn) {
    width: auto;
    height: auto;
  }
}
</style>
