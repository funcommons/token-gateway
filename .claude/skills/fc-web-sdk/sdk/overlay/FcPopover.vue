<script setup lang="ts">
defineOptions({ name: 'FcPopover' })
/**
 * FcPopover — 统一桌面 popover / 手机 drawer 的响应式弹层壳.
 *
 * 用法 A (一对一开关):
 *   <FcPopover v-model:open="open" title="..." :width="480">
 *     <template #trigger><button>...</button></template>
 *     <div>内容</div>
 *   </FcPopover>
 *
 * 用法 B (多个 selector 互斥, 共享一个 activeSelector 字段):
 *   <FcPopover :active="activeSelector === 'model'" @toggle="activeSelector = activeSelector === 'model' ? '' : 'model'">
 *     ...
 *   </FcPopover>
 *
 * 内置能力:
 * - 桌面 el-popover (trigger=click 自动 toggle) + flip/preventOverflow 默认开启
 * - 手机 el-drawer (默认 btt)
 * - 打开时自动锁 body 滚动 (useBodyScrollLock), 关闭自动解锁
 * - 抽屉贴屏幕边那一侧自动去圆角
 * - 桌面/手机共享同一个开关状态 (v-model:open 或 :active+@toggle)
 */
import { ref, computed, watch } from 'vue'
import { useResponsive } from '@/composables'
import { useBodyScrollLock } from '@/composables'

type Placement = 'top' | 'top-start' | 'top-end'
  | 'bottom' | 'bottom-start' | 'bottom-end'
  | 'left' | 'left-start' | 'left-end'
  | 'right' | 'right-start' | 'right-end'

interface Props {
  /** 方式 A: v-model:open 双向绑定开关状态 */
  open?: boolean
  /** 方式 B: 单向 active prop (配合 @toggle 事件, 适合多个 selector 互斥) */
  active?: boolean
  /** 手机端 el-drawer 的标题 (with-header=true 时显示) */
  title?: string
  /** 桌面端 el-popover 宽度 (number=px, string=CSS). 默认 'auto'. */
  width?: string | number
  /** 桌面端 popover 弹出位置, 默认 'bottom'. */
  placement?: Placement
  /** 触发方式, 默认 'click'. */
  trigger?: 'click' | 'hover' | 'focus' | 'manual'
  /** 是否显示小箭头, 默认 false. */
  showArrow?: boolean
  /** 手机端 drawer 滑入方向, 默认 'btt' (从下滑入, 适合短菜单). */
  drawerDirection?: 'rtl' | 'ltr' | 'ttb' | 'btt'
  /** 手机端 drawer 尺寸, 默认 'auto'. */
  drawerSize?: string
  /** 桌面 popover 自定义 class (追加到默认 'fc-popover-popper') */
  popperClass?: string
  /** 手机 drawer 自定义 class (追加到默认 'fc-popover-drawer') */
  drawerClass?: string
  /** 手机 drawer 是否显示 header (title 栏), 默认 true. 内容自带标题时设 false. */
  withHeader?: boolean
  /** 桌面 el-popover 的 popper-options; 默认开启 flip + preventOverflow 防溢出. */
  popperOptions?: Partial<import('@popperjs/core').Options>
}

const props = withDefaults(defineProps<Props>(), {
  open: false,
  active: undefined,
  title: '',
  width: 'auto',
  placement: 'bottom',
  trigger: 'click',
  showArrow: false,
  drawerDirection: 'btt',
  drawerSize: 'auto',
  popperClass: '',
  drawerClass: '',
  withHeader: true,
  popperOptions: () => ({
    modifiers: [
      { name: 'flip', options: { padding: 8, fallbackPlacements: ['top-start', 'bottom-start', 'top-end', 'bottom-end'] } },
      { name: 'preventOverflow', options: { padding: 8, mainAxis: true, altAxis: true } },
    ],
  }),
})

const emit = defineEmits<{
  'update:open': [boolean]
  /** 方式 B: 状态变化时触发, 调用方决定怎么更新自己的 activeSelector */
  toggle: [boolean]
}>()

const { isMobile } = useResponsive()

/** 内部 single source of truth. active 优先 (方式 B), 否则用 open (方式 A). */
const innerOpen = ref(props.active ?? props.open)

// 外部 prop 变化时同步到 innerOpen
watch(() => props.active, v => { if (v !== undefined) innerOpen.value = v })
watch(() => props.open, v => { if (props.active === undefined) innerOpen.value = v })

// innerOpen 变化时同步到外部
watch(innerOpen, v => {
  if (v !== props.open) emit('update:open', v)
  if (v !== props.active) emit('toggle', v)
})

function setOpen(v: boolean) { innerOpen.value = v }
function toggle() { innerOpen.value = !innerOpen.value }

// 打开时锁 body 滚动 (drawer 自带 lock-scroll, 这里只锁桌面 popover)
useBodyScrollLock(computed(() => innerOpen.value && !isMobile.value))

const popperClassFull = computed(() => ['fc-popover-popper', props.popperClass].filter(Boolean).join(' '))
const drawerClassFull = computed(() => ['fc-popover-drawer', props.drawerClass].filter(Boolean).join(' '))
</script>

<template>
  <!-- 桌面: el-popover, trigger=click 自动 toggle -->
  <el-popover
    v-if="!isMobile"
    v-model:visible="innerOpen"
    :placement="placement"
    :width="width"
    :trigger="trigger"
    :show-arrow="showArrow"
    :popper-class="popperClassFull"
    :popper-options="popperOptions"
  >
    <template #reference>
      <slot name="trigger" :open="innerOpen" :toggle="toggle" />
    </template>
    <slot :open="innerOpen" :close="() => setOpen(false)" />
  </el-popover>

  <!-- 手机: trigger slot 外包 display:contents 接 click 打开 drawer -->
  <template v-else>
    <div class="fc-popover-mobile-trigger" role="button" tabindex="0" @click="setOpen(true)" @keydown.enter="setOpen(true)" @keydown.space.prevent="setOpen(true)">
      <slot name="trigger" :open="innerOpen" :toggle="toggle" />
    </div>
    <el-drawer
      v-model="innerOpen"
      :title="withHeader ? title : undefined"
      :with-header="withHeader"
      :direction="drawerDirection"
      :size="drawerSize"
      :class="drawerClassFull"
    >
      <slot :open="innerOpen" :close="() => setOpen(false)" />
    </el-drawer>
  </template>
</template>

<style scoped>
/* display:contents 让 wrapper 在布局上"不存在" (子元素直接参与父级 flex/grid),
   但 click 事件仍能冒泡到这里被捕获. */
.fc-popover-mobile-trigger {
  display: contents;
}
</style>

<!-- 非 scoped: el-drawer teleport 到 body 后 scoped 失效, 用全局选择器 -->
<style>
/* 抽屉贴屏幕边那一侧不要圆角; 面向内容那一侧保留 8px. */
.fc-popover-drawer.el-drawer {
  border-radius: 8px !important;
}
.fc-popover-drawer.btt {
  border-bottom-left-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
.fc-popover-drawer.ttb {
  border-top-left-radius: 0 !important;
  border-top-right-radius: 0 !important;
}
.fc-popover-drawer.rtl {
  border-top-right-radius: 0 !important;
  border-bottom-right-radius: 0 !important;
}
.fc-popover-drawer.ltr {
  border-top-left-radius: 0 !important;
  border-bottom-left-radius: 0 !important;
}
</style>
