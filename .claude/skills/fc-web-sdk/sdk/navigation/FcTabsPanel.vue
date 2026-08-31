<template>
  <div class="fc-tabs-panel">
    <!-- Tab 栏 -->
    <div v-if="showTabsBar" class="fc-tabs">
      <button
        v-for="t in tabs"
        :key="t.value"
        class="fc-tab"
        :class="{ active: modelValue === t.value, disabled: t.disabled }"
        :disabled="t.disabled"
        @click="onTabClick(t)"
      >
        <i v-if="t.icon" :class="t.icon" />
        <span>{{ t.label }}</span>
      </button>
    </div>

    <!-- 内容区:每个 tab 一一对应 #tab-{value} slot;未匹配时显示 #default -->
    <div class="fc-tabs-content">
      <template v-for="t in tabs" :key="t.value">
        <div v-if="modelValue === t.value" class="fc-tab-pane">
          <slot :name="`tab-${t.value}`" />
        </div>
      </template>
      <slot v-if="!hasActiveSlot" name="default" />
    </div>

    <!-- 底部 slot -->
    <div v-if="$slots.footer" class="fc-tabs-footer">
      <slot name="footer" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcTabsPanel' })
import { computed } from 'vue'

export interface TabItem {
  value: string
  label: string
  icon?: string
  disabled?: boolean
}

interface Props {
  /** tab 列表 */
  tabs: TabItem[]
  /** 当前激活的 tab value (v-model) */
  modelValue: string
  /** 是否显示 tab 栏 (单 tab 时可隐藏) */
  showTabsBar?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  showTabsBar: true,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
  'tab-click': [value: string]
}>()

const hasActiveSlot = computed(() =>
  props.tabs.some(t => t.value === props.modelValue)
)

function onTabClick(t: TabItem) {
  if (t.disabled || t.value === props.modelValue) return
  emit('update:modelValue', t.value)
  emit('tab-click', t.value)
}
</script>

<style scoped lang="scss">
.fc-tabs-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.fc-tabs {
  display: flex;
  gap: 4px;
  padding: 4px;
  border-bottom: 1px solid var(--app-separator, #e5e5e5);
  flex-shrink: 0;
}

.fc-tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: none;
  background: transparent;
  color: var(--app-text-secondary, #666);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  border-radius: var(--radius-sm, 6px);
  transition: background 0.15s, color 0.15s;

  i {
    font-size: 16px;
  }

  &:hover:not(:disabled) {
    background: var(--app-bg-muted, #f5f5f5);
    color: var(--app-text-primary, #333);
  }

  &.active {
    background: color-mix(in srgb, var(--app-primary, #409eff) 12%, transparent);
    color: var(--app-primary, #409eff);
  }

  &:disabled,
  &.disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.fc-tabs-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.fc-tab-pane {
  padding: var(--space-md, 12px);
}

.fc-tabs-footer {
  padding: var(--space-sm, 8px) var(--space-md, 12px);
  border-top: 1px solid var(--app-separator, #e5e5e5);
  background: var(--app-bg-page, #fafafa);
  flex-shrink: 0;
}
</style>
