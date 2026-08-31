<script setup lang="ts">defineOptions({ name: 'FcSectionCard' })
/**
 * FcSectionCard — 基于 FcSection 的"短小交互卡"快捷方式.
 *
 * 跟 FcSection 区别:
 *  - FcSection = 通用页面分区, 默认无 padding/shadow/hover (由 CSS 变量或外层 class 控制)
 *  - FcSectionCard = 短小独立卡片, 默认 padding=md / shadow=sm, 可选 hover
 *
 * 实现: 内部 = FcSection + padding/shadow/hover prop 透传, 不重复样式.
 */
import FcSection from '../section/FcSection.vue'

interface Props {
  padding?: 'none' | 'sm' | 'md' | 'lg'
  shadow?: 'none' | 'sm' | 'md' | 'lg'
  hover?: boolean
}

withDefaults(defineProps<Props>(), {
  padding: 'md',
  shadow: 'sm',
  hover: false,
})
</script>

<template>
  <FcSection :hover="hover" :padding="padding" :shadow="shadow">
    <slot />
  </FcSection>
</template>
