<script setup lang="ts">
defineOptions({ name: 'FcFilterBarDivider' })
/**
 * FcFilterBarDivider — FcFilterBar 内的分隔竖线 (SDK).
 *
 * 用法:
 *   <FcFilterBar>
 *     <FcFilterButton ...>标签 1</FcFilterButton>
 *     <FcFilterBarDivider />
 *     <FcFilterButton ...>标签 2</FcFilterButton>
 *   </FcFilterBar>
 *
 * 历史: 由 components/common/FilterBarDivider.vue 迁入, 加 Fc 前缀.
 */
</script>

<template>
  <span class="fc-filter-bar-divider" />
</template>

<style scoped>
.fc-filter-bar-divider {
  width: 1px;
  height: 16px;
  background: var(--app-border-light);
  margin: 0 8px;
  flex-shrink: 0;
}
</style>
