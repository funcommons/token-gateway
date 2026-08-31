<script setup lang="ts">
// AppDrawer — 复用 WorkSection 视觉壳 + 背景高斯模糊 + 可调宽度 (EP ElDrawer 内置 resizable) 的 ElDrawer 包装 (SDK).
//
// 行为:
//   • resizable=true  → 用 EP 内置 resizable prop, 左侧出现 1px 拖拽条
//   • 背景高斯模糊 → modal-class="app-drawer-mask" 触发
//   • 视觉壳 → border-radius / border / shadow / padding 全部对齐
//             WorkSection (--app-radius-card, --app-block-pad, ...)
import { ElDrawer } from 'element-plus'

interface Props {
  visible: boolean
  title?: string
  direction?: 'rtl' | 'ltr' | 'ttb' | 'btt'
  size?: number | string
  resizable?: boolean
  closeOnClickModal?: boolean
  modalClass?: string
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  direction: 'rtl',
  size: 420,
  resizable: true,
  closeOnClickModal: false,
  modalClass: 'app-drawer-mask',
})

const emit = defineEmits<{
  'update:visible': [val: boolean]
  'resize': [size: number]
}>()

function onUpdateVisible(v: boolean) {
  emit('update:visible', v)
}
</script>

<template>
  <ElDrawer
    :model-value="visible"
    :title="title"
    :direction="direction"
    :size="size"
    :resizable="resizable"
    :close-on-click-modal="closeOnClickModal"
    :modal-class="modalClass"
    @update:model-value="onUpdateVisible"
    @resize="(e, sz) => emit('resize', sz)"
  >
    <slot />
    <template v-if="$slots.footer" #footer>
      <slot name="footer" />
    </template>
  </ElDrawer>
</template>

<style scoped>
:deep(.app-drawer-mask) {
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  background: rgba(0, 0, 0, 0.2);
}
:deep(.app-drawer-mask .el-drawer) {
  border-left: 1px solid var(--el-border-color-extra-light);
  box-shadow: -8px 0 32px rgba(0, 0, 0, 0.12);
}
:deep(.app-drawer-mask .el-drawer__header) {
  padding: var(--app-block-pad) calc(var(--app-block-pad) * 1.25);
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-extra-light);
  margin-bottom: 0;
}
:deep(.app-drawer-mask .el-drawer__title) {
  font-weight: 600;
  font-size: 15px;
  color: var(--app-text);
}
:deep(.app-drawer-mask .el-drawer__body) {
  padding: var(--app-block-pad);
  color: var(--app-text);
}
:deep(.app-drawer-mask .el-drawer__footer) {
  padding: var(--app-block-pad) calc(var(--app-block-pad) * 1.25);
  border-top: 1px solid var(--el-border-color-extra-light);
  background: var(--app-bg-muted);
}
</style>
