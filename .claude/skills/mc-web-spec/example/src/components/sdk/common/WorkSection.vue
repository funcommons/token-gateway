<script setup lang="ts">
// WorkSection — 视觉壳基类 (SDK).
// 仅提供 section 的样式 / 边框 / 阴影 + 内边距. 不带任何
// title / description 语义. 派生组件 (TitledSection / HeaderSection / KpiSection) 通过
// #header slot 提供语义层, 通过 default slot 提供 body.

interface Props {
  /** 移除 / 禁用 header 边框线 (KPI 类紧凑布局常用) */
  noHeaderBorder?: boolean
}
withDefaults(defineProps<Props>(), { noHeaderBorder: false })
</script>

<template>
  <section class="work-section">
    <div
      v-if="$slots.header"
      class="work-section__header"
      :class="{ 'work-section__header--flush': noHeaderBorder }"
    >
      <slot name="header" />
    </div>
    <div class="work-section__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.work-section {
  background: var(--el-bg-color);
  border-radius: var(--app-radius-card);
  box-shadow: var(--app-shadow-md);
  border: 1px solid var(--el-border-color-extra-light);
  /* padding + margin-bottom 由 src/styles/index.scss 的 .app-main > .my-section
     全局规则统一控制 (4/8/16 响应式) */
}
.work-section__header {
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.work-section__header--flush {
  border-bottom: none;
}
.work-section__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* 内部去线框化: 字段边框保留 (el-input / el-select), 区块边框剥掉 */
.work-section :deep(.el-card),
.work-section :deep(.my-card) {
  background: transparent;
  border: none;
  box-shadow: none;
  border-radius: 0;
  padding: 0;
}
.work-section :deep(.el-divider) {
  display: none;
}
.work-section :deep(.el-descriptions__body),
.work-section :deep(.el-descriptions .el-descriptions__table),
.work-section :deep(.el-descriptions .el-descriptions__cell),
.work-section :deep(.el-descriptions__label),
.work-section :deep(.el-descriptions__content) {
  border: none !important;
  background: transparent !important;
}
.work-section :deep(.el-descriptions .el-descriptions__table) {
  border-collapse: separate;
  border-spacing: 0;
}
</style>
