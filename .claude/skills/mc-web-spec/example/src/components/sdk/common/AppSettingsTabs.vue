<script setup lang="ts">
// AppSettingsTabs — 设置页签容器 (SDK), 封装:
//   • EP el-tabs 的设置页视觉壳 (白底 + 1px 边框 + 圆角 + 阴影, 对齐 WorkSection)
//   • tabPosition 自适应: 桌面 left, 移动端 top
//   • 移动端 tab label 改成 icon-on-top 紧凑布局
//
// 内部用 useResponsive (peer dep). host 在 tab key 对应的命名 slot 里填内容.
import { computed } from 'vue'
import { useResponsive } from '@/composables/useResponsive'

interface Tab {
  key: string
  label: string
  icon: string
}

interface Props {
  /** 当前激活的 tab key, v-model 双向绑定 */
  active: string
  /** tab 列表 */
  tabs: Tab[]
}

const props = defineProps<Props>()
const emit = defineEmits<{
  'update:active': [val: string]
}>()

const { isMobile } = useResponsive()

const tabPosition = computed<'top' | 'left'>(() => (isMobile.value ? 'top' : 'left'))

function onTabChange(name: string | number) {
  emit('update:active', String(name))
}
</script>

<template>
  <el-tabs
    :model-value="active"
    :tab-position="tabPosition"
    class="app-settings-tabs"
    :class="{ 'is-mobile': isMobile }"
    @update:model-value="onTabChange"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.key" :name="tab.key">
      <template #label>
        <span class="app-settings-tab-label">
          <i :class="tab.icon" />
          <span>{{ tab.label }}</span>
        </span>
      </template>
      <slot :name="tab.key" :tab="tab" />
    </el-tab-pane>
  </el-tabs>
</template>

<style scoped>
.app-settings-tabs {
  background: transparent;
  border: none;
  border-radius: 0;
  box-shadow: none;
  padding: 0;
  margin: 0;
  min-height: 600px;
}
.app-settings-tabs :deep(.el-tabs__header) {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--brand-radius-card);
  box-shadow: var(--brand-shadow-md);
  padding: 8px;
  margin: 0;
}
.app-settings-tabs :deep(.el-tabs__header.is-left)   { margin-right:  var(--app-block-mb); }
.app-settings-tabs :deep(.el-tabs__header.is-right)  { margin-left:   var(--app-block-mb); }
.app-settings-tabs :deep(.el-tabs__header.is-top)    { margin-bottom: var(--app-block-mb); }
.app-settings-tabs :deep(.el-tabs__header.is-bottom) { margin-top:    var(--app-block-mb); }
.app-settings-tabs.is-mobile :deep(.el-tabs__header) {
  padding: 4px;
}
.app-settings-tabs :deep(.el-tabs__content) {
  padding: 0;
  margin: 0;
}
.app-settings-tabs :deep(.el-tab-pane) {
  padding: 0;
  margin: 0;
}
.app-settings-tabs :deep(.el-tabs__item) {
  height: 44px;
  line-height: 44px;
  padding: 0 16px !important;
  text-align: left;
}
.app-settings-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.app-settings-tab-label i {
  font-size: 18px;
}

/* Mobile: compact icon-on-top layout */
.app-settings-tabs.is-mobile :deep(.el-tabs__nav-wrap) {
  margin-bottom: 8px;
}
.app-settings-tabs.is-mobile :deep(.el-tabs__item) {
  height: 52px;
  padding: 4px 8px !important;
  line-height: 1.1 !important;
  text-align: center;
  font-size: 12px;
}
.app-settings-tabs.is-mobile :deep(.el-tabs__item .app-settings-tab-label) {
  flex-direction: column;
  gap: 2px;
}
.app-settings-tabs.is-mobile :deep(.el-tabs__item .app-settings-tab-label i) {
  font-size: 18px;
}
.app-settings-tabs.is-mobile :deep(.el-tabs__item .app-settings-tab-label span) {
  font-size: 12px;
  line-height: 1.2;
  max-width: 64px;
  word-break: break-all;
}
</style>
