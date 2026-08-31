<script setup lang="ts">defineOptions({ name: 'FcSection' })
interface Props {
  noHeaderBorder?: boolean
  /** 内边距档位. 默认 inherit (沿用 .fc-main .fc-section 全局响应式 padding) */
  padding?: 'inherit' | 'none' | 'sm' | 'md' | 'lg'
  /** 阴影档位. 默认 md (跟 WorkSection 一致) */
  shadow?: 'none' | 'sm' | 'md' | 'lg'
  /** 是否可 hover 交互 (整张卡片轻微浮起). 默认 false */
  hover?: boolean
}
withDefaults(defineProps<Props>(), {
  noHeaderBorder: false,
  padding: 'inherit',
  shadow: 'md',
  hover: false,
})
</script>

<template>
  <section
    class="fc-section"
    :class="[
      `padding-${padding}`,
      `shadow-${shadow}`,
      { 'is-hoverable': hover }
    ]"
  >
    <div
      v-if="$slots.header"
      class="fc-section__header"
      :class="{ 'fc-section__header--flush': noHeaderBorder }"
    >
      <slot name="header" />
    </div>
    <div class="fc-section__body">
      <slot />
    </div>
  </section>
</template>

<style scoped>
/* 跟 /mc-web-spec example WorkSection.vue 对齐 — 类名 fc-section, 默认带
   bg / radius / shadow / border, padding 由 .fc-main .fc-section 全局
   响应式规则统一控制 (4/8/16). */
.fc-section {
  background: var(--el-bg-color);
  border-radius: var(--app-radius-card);
  box-shadow: var(--app-shadow-md);
  border: 1px solid var(--el-border-color-extra-light);
  transition: all var(--transition-base);
}
.fc-section__header {
  border-bottom: 1px solid var(--el-border-color-extra-light);
}
.fc-section__header--flush {
  border-bottom: none;
}
.fc-section__body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* ---- opt-in modifier: padding 覆盖全局 .fc-main .fc-section ---- */
.fc-section.padding-none { padding: 0; }
.fc-section.padding-sm { padding: var(--space-sm); }
.fc-section.padding-md { padding: var(--space-md); }
.fc-section.padding-lg { padding: var(--space-lg); }

/* ---- opt-in modifier: shadow 覆盖默认 var(--app-shadow-md) ---- */
.fc-section.shadow-none { box-shadow: none; }
.fc-section.shadow-sm { box-shadow: var(--shadow-sm); }
.fc-section.shadow-lg { box-shadow: var(--shadow-lg); }

/* ---- opt-in: 整卡 hover 浮起 ---- */
.fc-section.is-hoverable {
  cursor: pointer;
  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--shadow-lg);
  }
}

/* 内部去线框化: 字段边框保留 (el-input / el-select), 区块边框剥掉
   遵循 auto-memory: WorkSection 内不要大线框/灰色背景, 用分隔线 */
.fc-section :deep(.el-card),
.fc-section :deep(.my-card) {
  background: transparent;
  border: none;
  box-shadow: none;
  border-radius: 0;
  padding: 0;
}
.fc-section :deep(.el-divider) {
  display: none;
}
.fc-section :deep(.el-descriptions__body),
.fc-section :deep(.el-descriptions .el-descriptions__table),
.fc-section :deep(.el-descriptions .el-descriptions__cell),
.fc-section :deep(.el-descriptions__label),
.fc-section :deep(.el-descriptions__content) {
  border: none !important;
  background: transparent !important;
}
.fc-section :deep(.el-descriptions .el-descriptions__table) {
  border-collapse: separate;
  border-spacing: 0;
}
</style>