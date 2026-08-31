<script setup lang="ts">
defineOptions({ name: 'FcTooltip', inheritAttrs: false })
/**
 * FcTooltip — el-tooltip 薄封装 (SDK).
 *
 * 替代业务侧散落 <el-tooltip class="custom-tip"> + 自写 :deep 覆写.
 * 统一字体/颜色/padding/delay, 桌面 hover 触发, 移动端降级为长按.
 *
 * 用法:
 *   <FcTooltip :content="t('help.hint')" placement="top">
 *     <el-icon><Question /></el-icon>
 *   </FcTooltip>
 *   <FcTooltip content="删除后无法恢复" variant="danger">
 *     <FcButton variant="danger">删除</FcButton>
 *   </FcTooltip>
 */
import { computed, useSlots } from 'vue'

type TooltipPlacement = 'top' | 'top-start' | 'top-end'
  | 'bottom' | 'bottom-start' | 'bottom-end'
  | 'left' | 'left-start' | 'left-end'
  | 'right' | 'right-start' | 'right-end'

interface Props {
  /** 提示文案. */
  content?: string
  /** 位置. 默认 'top'. */
  placement?: TooltipPlacement
  /** 视觉变体. 'danger' 用红色字. */
  variant?: 'default' | 'danger'
  /** 是否禁用. */
  disabled?: boolean
  /** 是否显示箭头. 默认 true. */
  showArrow?: boolean
  /** 进入延迟 (ms). 默认 100. */
  showDelay?: number
  /** 离开延迟 (ms). 默认 100. */
  hideDelay?: number
  /** 是否纯文字. 默认 true. (false 时显示深色背景). */
  light?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  placement: 'top',
  variant: 'default',
  disabled: false,
  showArrow: true,
  showDelay: 100,
  hideDelay: 100,
  light: true,
})

const slots = useSlots()
const hasDefaultSlot = computed(() => !!slots.default)

const popperClass = computed(() => [
  'fc-tooltip',
  `variant-${props.variant}`,
  { 'is-light': props.light, 'is-dark': !props.light },
].filter(Boolean).join(' '))
</script>

<template>
  <el-tooltip
    v-if="hasDefaultSlot"
    :content="content"
    :placement="placement"
    :disabled="disabled"
    :show-arrow="showArrow"
    :show-delay="showDelay"
    :hide-delay="hideDelay"
    :popper-class="popperClass"
    :virtual-triggering="false"
  >
    <slot />
  </el-tooltip>
  <span v-else class="fc-tooltip-empty" />
</template>

<style>
/* 非 scoped: el-tooltip teleport 到 body */
.fc-tooltip {
  --app-tooltip-bg: var(--app-bg-card, #fff);
  --app-tooltip-text: var(--app-text, #333);

  font-size: 12px;
  line-height: 1.5;
  padding: 6px 10px;
  border-radius: var(--app-radius-sm, 4px);
  max-width: 280px;
  word-break: break-word;
}

.fc-tooltip.is-light {
  background: var(--app-tooltip-bg);
  color: var(--app-tooltip-text);
  border: 1px solid var(--app-separator, #e5e5e5);
  box-shadow: var(--app-shadow-sm, 0 2px 8px rgba(0, 0, 0, 0.08));
}

.fc-tooltip.is-dark {
  background: #1f1f1f;
  color: #fff;
  border: 1px solid #1f1f1f;
}

.fc-tooltip.variant-danger {
  --app-tooltip-text: var(--app-color-danger, #ff3b30);
}

.fc-tooltip-empty {
  display: inline-block;
}
</style>
