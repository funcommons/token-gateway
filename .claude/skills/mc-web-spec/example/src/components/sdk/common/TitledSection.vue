<script setup lang="ts">
// TitledSection — 通用 "有标题的块" (title + description + body) (SDK).
// 继承 WorkSection, 视觉壳统一. 标题语义在 #header slot 内提供.
import { useSlots } from 'vue'
import WorkSection from '@/components/sdk/common/WorkSection.vue'

interface Props {
  title: string
  description?: string
}
defineProps<Props>()
const slots = useSlots()
</script>

<template>
  <WorkSection>
    <template #header>
      <h2 class="titled-section__title">
        <slot name="icon">
          <i class="ri-bookmark-line" />
        </slot>
        {{ title }}
      </h2>
      <p v-if="description" class="titled-section__desc">{{ description }}</p>
    </template>
    <div class="titled-section__body" :class="{ 'titled-section__body--has-grid': !!slots.grid }">
      <slot />
    </div>
  </WorkSection>
</template>

<style scoped>
/* 仅保留 title / desc / body 的语义样式 (类名从 .my-section__* 改为 .titled-section__*).
   视觉壳已由 WorkSection 提供. */
.titled-section__title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-weight: 600;
  color: var(--app-text);
}
.titled-section__title i {
  color: var(--app-primary);
  font-size: 18px;
}
.titled-section__desc {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--app-text-secondary);
}
</style>
