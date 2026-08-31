<script setup lang="ts">
defineOptions({ name: 'FcFilterBar' })
/**
 * FcFilterBar — 一行水平排列的筛选标签容器 (SDK).
 *
 * 跟 FcFilterButton / FcFilterBarDivider 配套用. 多选筛选语义.
 *
 * 用法:
 *   <FcFilterBar>
 *     <FcFilterButton :active="..." @click="...">全部</FcFilterButton>
 *     <FcFilterButton :active="..." @click="...">视频</FcFilterButton>
 *     <FcFilterBarDivider />
 *     <FcFilterButton :active="..." @click="...">4K</FcFilterButton>
 *   </FcFilterBar>
 *
 * 历史: 由 components/common/FilterBar.vue 迁入, 加 Fc 前缀.
 */
interface Props {
  /** 是否全宽 (justify-content: flex-start 不变, 但 width: 100%). 默认 false. */
  block?: boolean
}
withDefaults(defineProps<Props>(), { block: false })
</script>

<template>
  <div class="fc-filter-bar" :class="{ 'is-block': block }">
    <slot />
  </div>
</template>

<style scoped lang="scss">
.fc-filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;

  &.is-block {
    width: 100%;
  }
}
</style>
