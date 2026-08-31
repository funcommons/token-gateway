<script setup lang="ts">
defineOptions({ name: 'FcSegmented' })
/**
 * FcSegmented — 分段控件 (单选胶囊, SDK).
 *
 * 跟 FilterBar/FilterButton 区别:
 *  - FilterBar = 多选筛选标签 (语义: 标签云)
 *  - FcSegmented = 单选必选切换 (语义: 模式/视图/Tab 切换)
 *
 * 视觉: 紧凑相邻胶囊, 选中态实底色 + 阴影, 选中态有平滑滑动指示器 (可选).
 *
 * 用法 A (对象数组, 推荐):
 *   <FcSegmented
 *     v-model="mode"
 *     :options="[
 *       { label: '列表', value: 'list', icon: 'ri-list-check' },
 *       { label: '网格', value: 'grid', icon: 'ri-grid-line' },
 *     ]"
 *   />
 *
 * 用法 B (slot, 完全自定义):
 *   <FcSegmented v-model="mode">
 *     <FcSegmentedOption value="list">列表</FcSegmentedOption>
 *     <FcSegmentedOption value="grid">网格</FcSegmentedOption>
 *   </FcSegmented>
 */
import { computed } from 'vue'

interface SegOption {
  value: string | number
  label?: string
  /** remixicon class, 如 'ri-list-check' */
  icon?: string
  disabled?: boolean
}

interface Props {
  /** v-model */
  modelValue?: string | number
  /** 选项数组. 跟 slot 二选一. */
  options?: SegOption[]
  /** 尺寸. */
  size?: 'sm' | 'md' | 'lg'
  /** 是否全宽 (justify-content: stretch, 每个等宽). 默认 false. */
  block?: boolean
  /** 是否禁用整组. */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: '',
  options: () => [],
  size: 'md',
  block: false,
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string | number]
  change: [value: string | number]
}>()

function pick(v: string | number, optDisabled?: boolean) {
  if (props.disabled || optDisabled) return
  if (v === props.modelValue) return
  emit('update:modelValue', v)
  emit('change', v)
}

const sizeClass = computed(() => `size-${props.size}`)
</script>

<template>
  <div
    class="fc-segmented"
    :class="[sizeClass, { 'is-block': block, 'is-disabled': disabled }]"
    role="radiogroup"
  >
    <template v-if="options.length">
      <button
        v-for="opt in options"
        :key="opt.value"
        type="button"
        class="fc-segmented__item"
        :class="{
          'is-active': modelValue === opt.value,
          'is-disabled': opt.disabled || disabled,
        }"
        role="radio"
        :aria-checked="modelValue === opt.value"
        :tabindex="modelValue === opt.value ? 0 : -1"
        :disabled="opt.disabled || disabled"
        @click="pick(opt.value, opt.disabled)"
      >
        <i v-if="opt.icon" :class="opt.icon" />
        <span v-if="opt.label">{{ opt.label }}</span>
      </button>
    </template>
    <template v-else>
      <slot />
    </template>
  </div>
</template>

<style scoped lang="scss">
.fc-segmented {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px;
  background: var(--app-bg-muted, #f5f5f5);
  border-radius: var(--app-radius-md, 10px);
  border: 1px solid var(--app-border-extra-light, transparent);

  &.is-block {
    display: flex;
    width: 100%;

    .fc-segmented__item {
      flex: 1;
    }
  }

  &.is-disabled {
    opacity: 0.5;
    pointer-events: none;
  }
}

.fc-segmented__item {
  appearance: none;
  border: none;
  background: transparent;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  font-weight: 500;
  color: var(--app-text-secondary);
  border-radius: calc(var(--app-radius-md, 10px) - 2px);
  transition: background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease;
  white-space: nowrap;

  &:hover:not(.is-disabled):not(.is-active) {
    color: var(--app-text);
  }

  &.is-active {
    background: var(--app-bg-card, #fff);
    color: var(--app-primary, #409eff);
    box-shadow: var(--app-shadow-md, 0 1px 3px rgba(0, 0, 0, 0.08));
  }

  &.is-disabled {
    cursor: not-allowed;
  }

  i {
    font-size: inherit;
  }
}

/* 尺寸 */
.size-sm .fc-segmented__item {
  padding: 4px 10px;
  font-size: 12px;
  min-height: 24px;
}
.size-md .fc-segmented__item {
  padding: 6px 14px;
  font-size: 13px;
  min-height: 30px;
}
.size-lg .fc-segmented__item {
  padding: 8px 18px;
  font-size: 14px;
  min-height: 36px;
}
</style>
