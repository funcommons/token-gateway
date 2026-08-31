<script setup lang="ts">
// KpiLayout — KPI 网格容器 (SDK), 用于排列多个 KpiSection.
// 桌面端 columns 列 (默认 4), 移动端 mobileColumns 列 (默认 2).
import { provide } from 'vue'

interface Props {
  /** 桌面 / 平板 (>768px) 的列数 */
  columns?: number
  /** 移动端 (<=768px) 的列数 */
  mobileColumns?: number
  /** 单元格间距, 接受 CSS 字符串 (如 "16px") */
  gap?: string
}
const props = withDefaults(defineProps<Props>(), {
  columns: 4,
  mobileColumns: 2,
  gap: 'var(--app-block-mb)',
})

provide('kpiLayoutColumns', props.columns)
provide('kpiLayoutMobileColumns', props.mobileColumns)
</script>

<template>
  <div
    class="kpi-layout"
    :style="{
      '--kpi-cols': String(columns),
      '--kpi-cols-mobile': String(mobileColumns),
      '--kpi-gap': gap,
    }"
  >
    <slot />
  </div>
</template>

<style scoped>
.kpi-layout {
  display: grid;
  grid-template-columns: repeat(var(--kpi-cols-mobile, 2), minmax(0, 1fr));
  gap: var(--kpi-gap);
}
@media (min-width: 768px) {
  .kpi-layout {
    grid-template-columns: repeat(var(--kpi-cols, 4), minmax(0, 1fr));
  }
}
/* 网格内的 WorkSection 默认有 margin-bottom, 网格场景下需要去掉 */
.kpi-layout :deep(.work-section) {
  margin-bottom: 0;
}
</style>
