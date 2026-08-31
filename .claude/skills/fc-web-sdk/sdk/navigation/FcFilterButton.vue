<script setup lang="ts">
defineOptions({ name: 'FcFilterButton' })
/**
 * FcFilterButton — 筛选标签按钮 (pill 形, SDK).
 *
 * 视觉: 8px 20px padding, 20px chip radius, 浅边框, hover 浅 muted 底,
 *       active 用品牌主色实底 + 阴影.
 *
 * 跟 FcFilterBar 配套 (放 FcFilterBar 默认 slot 内).
 * 跟 FcSegmented 区别: FcFilterButton 是离散筛选标签 (可多选语义),
 *                     FcSegmented 是分段单选必选切换.
 *
 * 用法:
 *   <FcFilterButton :active="selected === opt.value" @click="selected = opt.value">
 *     {{ opt.label }}
 *     <template #badge>{{ opt.count }}</template>
 *   </FcFilterButton>
 *
 * 历史: 由 components/common/FilterButton.vue 迁入, 加 Fc 前缀.
 */
interface Props {
  /** 当前激活 (is-active class). 默认 false */
  active?: boolean
  /** 禁用 */
  disabled?: boolean
}
withDefaults(defineProps<Props>(), {
  active: false,
  disabled: false,
})

const emit = defineEmits<{ click: [] }>()
</script>

<template>
  <button
    class="fc-filter-btn"
    :class="{ 'is-active': active }"
    :disabled="disabled"
    @click="emit('click')"
  >
    <slot />
    <span v-if="$slots.badge" class="fc-filter-btn__count">
      <slot name="badge" />
    </span>
  </button>
</template>

<style scoped lang="scss">
@use '@/styles/mixins' as *;

.fc-filter-btn {
  @include reset-button;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 20px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-chip, 20px);
  font-size: 12px;
  font-weight: 700;
  color: var(--app-text-secondary);
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease, box-shadow 0.15s ease;

  &:hover:not(:disabled) {
    background: var(--app-bg-muted);
    color: var(--app-text);
  }

  &.is-active {
    background: var(--app-primary);
    color: var(--app-on-primary, #fff);
    border-color: var(--app-primary);
    box-shadow: var(--app-shadow-md);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.fc-filter-btn__count {
  min-width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 6px;
  background: color-mix(in srgb, var(--app-text) 10%, transparent);
  border-radius: var(--app-radius-full, 9999px);
  font-size: 11px;
  font-weight: 700;
  line-height: 1;

  .is-active & {
    background: color-mix(in srgb, var(--app-on-primary, #fff) 25%, transparent);
  }
}
</style>
