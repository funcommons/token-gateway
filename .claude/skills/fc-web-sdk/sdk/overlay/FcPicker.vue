<template>
  <!--
    FcPicker — 通用 popover 风格选择器壳.
    提供 trigger + popup (dialog/drawer) + context menu + action sheet 的开/关状态管理.
    业务层通过 slot 注入:
      - trigger        : 触发器区域
      - panel          : popup 内的内容
      - context-menu   : 右键菜单 (一般用 FcContextMenu)
      - action-sheet   : 手机端底部菜单
  -->
  <div
    class="fc-picker"
    :class="{ 'is-disabled': disabled }"
    @click="onTriggerClick"
    @contextmenu.prevent="onContextMenu"
  >
    <slot name="trigger" />

    <!-- 桌面: 居中 dialog -->
    <FcDialog
      v-if="!isMobile"
      v-model:open="dialogOpen"
      :title="title"
      :width="dialogWidth"
      :height="dialogHeight"
      :draggable="draggable"
      :resizable="resizable"
      :close-on-click-modal="closeOnClickModal"
      :append-to-body="appendToBody"
      dialog-class="fc-picker-dialog"
    >
      <slot name="panel" :close="closeDialog" />
    </FcDialog>

    <!-- 手机: 底部 drawer -->
    <FcDrawer
      v-else
      v-model:open="drawerOpen"
      direction="btt"
      :size="drawerSize"
      :with-header="false"
      drawer-class="fc-picker-drawer"
    >
      <slot name="panel" :close="closeDrawer" />
    </FcDrawer>

    <!-- 右键菜单 (slot 注入, 一般是 FcContextMenu; 通过 :open 控制显隐, 避免卸载丢失动画) -->
    <slot
      name="context-menu"
      :pos="ctxPos"
      :close="closeContextMenu"
      :open="ctxOpen"
    />

    <!-- 手机: action sheet -->
    <FcDrawer
      v-if="isMobile && $slots['action-sheet']"
      v-model:open="actionSheetOpen"
      direction="btt"
      size="auto"
      :with-header="false"
      drawer-class="fc-picker-action-sheet"
    >
      <slot name="action-sheet" :close="closeActionSheet" />
    </FcDrawer>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcPicker' })
import { ref } from 'vue'
import FcDialog from './FcDialog.vue'
import FcDrawer from './FcDrawer.vue'

interface Props {
  /** dialog 标题 */
  title?: string
  /** dialog 宽度 (px) */
  dialogWidth?: number
  /** dialog 高度 (px) */
  dialogHeight?: number | string
  /** drawer 高度 (CSS 长度) */
  drawerSize?: string
  /** 触发器点击行为:
   *  - open   : 打开 popup (默认)
   *  - menu   : 打开 action sheet (手机) / context menu (桌面)
   *  - none   : 触发器不响应 (由父组件手动调用 open 方法) */
  triggerAction?: 'open' | 'menu' | 'none'
  /** 禁用整个 picker */
  disabled?: boolean
  /** 是否手机端 (用 drawer 代替 dialog) */
  isMobile?: boolean
  /** dialog 可拖动 */
  draggable?: boolean
  /** dialog 可调大小 */
  resizable?: boolean
  /** 点击遮罩关闭 */
  closeOnClickModal?: boolean
  /** dialog 挂到 body */
  appendToBody?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  title: '',
  dialogWidth: 480,
  dialogHeight: undefined,
  drawerSize: '70%',
  triggerAction: 'open',
  disabled: false,
  isMobile: false,
  draggable: true,
  resizable: false,
  closeOnClickModal: true,
  appendToBody: false,
})

const emit = defineEmits<{
  open: []
  close: []
  'context-menu': [pos: { x: number; y: number }]
}>()

const dialogOpen = ref(false)
const drawerOpen = ref(false)
const actionSheetOpen = ref(false)
const ctxOpen = ref(false)
const ctxPos = ref({ x: 0, y: 0 })

// === 公共开关方法 (供父组件手动调用, 支持 inline 模式) ===
function openDialog() {
  if (props.isMobile) {
    drawerOpen.value = true
  } else {
    dialogOpen.value = true
  }
  emit('open')
}
function closeDialog() {
  dialogOpen.value = false
  emit('close')
}
function closeDrawer() {
  drawerOpen.value = false
  emit('close')
}
function openActionSheet() {
  actionSheetOpen.value = true
}
function closeActionSheet() {
  actionSheetOpen.value = false
}
function openContextMenu(pos: { x: number; y: number }) {
  ctxPos.value = pos
  ctxOpen.value = true
  emit('context-menu', pos)
}
function closeContextMenu() {
  ctxOpen.value = false
}

// === 触发器默认行为 ===
function onTriggerClick() {
  if (props.disabled) return
  if (props.triggerAction === 'none') return
  if (props.triggerAction === 'menu') {
    if (props.isMobile) openActionSheet()
    else openContextMenu({ x: 0, y: 0 })
    return
  }
  openDialog()
}

function onContextMenu(e: MouseEvent) {
  if (props.disabled) return
  openContextMenu({ x: e.clientX, y: e.clientY })
}

defineExpose({
  openDialog,
  closeDialog,
  closeDrawer,
  openActionSheet,
  closeActionSheet,
  openContextMenu,
  closeContextMenu,
  isDialogOpen: () => dialogOpen.value,
})
</script>

<style scoped lang="scss">
.fc-picker {
  display: inline-block;
  position: relative;

  &.is-disabled {
    cursor: not-allowed;
    pointer-events: none;
    opacity: 0.6;
  }
}
</style>

<style lang="scss">
/* picker-dialog: panel 撑满 dialog body 高度 (固定 dialogHeight 时内容不塌缩) */
.fc-picker-dialog {
  display: flex;
  flex-direction: column;

  .el-dialog__header {
    flex: 0 0 auto;
  }
  .el-dialog__body {
    flex: 1 1 auto;
    min-height: 0;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }
  .el-dialog__footer {
    flex: 0 0 auto;
  }
}
</style>
