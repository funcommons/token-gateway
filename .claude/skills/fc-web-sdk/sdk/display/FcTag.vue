<script setup lang="ts">
defineOptions({ name: 'FcTag' })
/**
 * FcTag — 通用标签 / chip (SDK).
 *
 * 跟 FcTagGroup 配合 (group 是容器 + 可编辑 input, FcTag 是单个原子).
 * 替代项目里 el-tag 跟自写 .tag class 混用.
 *
 * color:
 *  - primary : 品牌主色浅底 (默认)
 *  - gray    : 中性灰
 *  - success : 绿
 *  - warning : 橙
 *  - danger  : 红
 *  - brand   : 跟主题 token 联动 (品牌切换跟着变)
 *
 * size: sm (默认) / md / lg
 *
 * 用法:
 *   <FcTag>{{ t('work.tag-trending') }}</FcTag>
 *   <FcTag color="danger" :closable="true" @close="onClose">违规</FcTag>
 *   <FcTag color="brand" solid>{{ t('common.featured') }}</FcTag>
 */
import { computed } from 'vue'

type TagColor = 'primary' | 'gray' | 'success' | 'warning' | 'danger' | 'brand'

interface Props {
  /** 颜色 tone. */
  color?: TagColor
  /** 尺寸. */
  size?: 'sm' | 'md' | 'lg'
  /** 是否实底 (满色). 默认 false (浅底). */
  solid?: boolean
  /** 是否可关闭 (显示 ×). */
  closable?: boolean
  /** 是否禁用. */
  disabled?: boolean
  /** 是否选中 (业务多选时). */
  selected?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  color: 'primary',
  size: 'sm',
  solid: false,
  closable: false,
  disabled: false,
  selected: false,
})

const emit = defineEmits<{
  close: []
  click: []
}>()

const classes = computed(() => [
  `color-${props.color}`,
  `size-${props.size}`,
  {
    'is-solid': props.solid,
    'is-disabled': props.disabled,
    'is-selected': props.selected,
    'is-clickable': !props.disabled,
  },
])
</script>

<template>
  <span
    class="fc-tag"
    :class="classes"
    role="tag"
    :tabindex="disabled ? -1 : 0"
    @click="!disabled && emit('click')"
    @keydown.enter.prevent="!disabled && emit('click')"
  >
    <span class="fc-tag__content">
      <slot />
    </span>
    <button
      v-if="closable"
      type="button"
      class="fc-tag__close"
      :tabindex="disabled ? -1 : 0"
      :disabled="disabled"
      :aria-label="'close'"
      @click.stop="!disabled && emit('close')"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4">
        <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round"/>
      </svg>
    </button>
  </span>
</template>

<style scoped lang="scss">
.fc-tag {
  --tag-color: var(--app-primary, #409eff);

  display: inline-flex;
  align-items: center;
  gap: 4px;
  border-radius: var(--app-radius-chip, 9999px);
  font-weight: 600;
  line-height: 1.2;
  letter-spacing: 0.01em;
  white-space: nowrap;
  background: color-mix(in srgb, var(--tag-color) 12%, transparent);
  color: var(--tag-color);
  transition: background 0.15s ease, color 0.15s ease, transform 0.1s ease;
  user-select: none;

  /* color tokens */
  &.color-primary { --tag-color: var(--app-primary, #409eff); }
  &.color-gray    { --tag-color: var(--app-text-tertiary, #999); }
  &.color-success { --tag-color: var(--app-color-success, #67c23a); }
  &.color-warning { --tag-color: var(--app-color-warning, #e6a23c); }
  &.color-danger  { --tag-color: var(--app-color-danger, #ff3b30); }
  &.color-brand   { --tag-color: var(--brand-primary, var(--app-primary, #409eff)); }

  /* solid variant */
  &.is-solid {
    background: var(--tag-color);
    color: var(--app-on-primary, #fff);
  }

  /* sizes */
  &.size-sm {
    padding: 2px 8px;
    font-size: 11px;
  }
  &.size-md {
    padding: 4px 12px;
    font-size: 12px;
  }
  &.size-lg {
    padding: 6px 14px;
    font-size: 13px;
  }

  /* interactive states */
  &.is-clickable {
    cursor: pointer;

    &:hover {
      background: color-mix(in srgb, var(--tag-color) 18%, transparent);
    }
    &.is-solid:hover {
      filter: brightness(0.95);
    }
    &:active {
      transform: scale(0.96);
    }
    &.is-selected {
      background: var(--tag-color);
      color: var(--app-on-primary, #fff);
    }
  }

  &.is-disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.fc-tag__content {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.fc-tag__close {
  appearance: none;
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  color: inherit;
  opacity: 0.6;
  transition: opacity 0.15s, background 0.15s;

  &:hover:not(:disabled) {
    opacity: 1;
    background: rgba(0, 0, 0, 0.1);
  }

  &:disabled {
    cursor: not-allowed;
  }

  svg {
    width: 10px;
    height: 10px;
    display: block;
  }
}
</style>
