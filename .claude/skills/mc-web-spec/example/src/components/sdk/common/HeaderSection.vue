<script setup lang="ts">
// HeaderSection — 页面顶部标题块 (title + subtitle + 可选返回 + actions + 可选 welcome 横幅) (SDK).
// 继承 WorkSection, 视觉壳统一. #welcome slot 可选, 用于在标题行下方追加欢迎横幅.
//
// 返回按钮改用 emit('back') (SDK 不绑 vue-router), host 监听 @back 决定行为.
import WorkSection from '@/components/sdk/common/WorkSection.vue'

interface Props {
  title: string
  subtitle?: string
  back?: boolean
}
withDefaults(defineProps<Props>(), { subtitle: '', back: false })

const emit = defineEmits<{
  back: []
}>()

function onBack() {
  emit('back')
}
</script>

<template>
  <WorkSection no-header-border>
    <template #header>
      <div class="header-section__row">
        <div class="header-section__main">
          <el-button v-if="back" text @click="onBack">
            <i class="ri-arrow-left-line" />
          </el-button>
          <div>
            <h1 class="header-section__title">{{ title }}</h1>
            <p v-if="subtitle" class="header-section__subtitle">{{ subtitle }}</p>
          </div>
        </div>
        <div class="header-section__actions">
          <slot name="actions" />
        </div>
      </div>
    </template>
    <div v-if="$slots.welcome" class="header-section__welcome">
      <slot name="welcome" />
    </div>
  </WorkSection>
</template>

<style scoped>
.header-section__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.header-section__main {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-section__title {
  margin: 0;
  font-size: var(--app-font-size-header);
  font-weight: 600;
  color: var(--app-text);
}
.header-section__subtitle {
  margin: 4px 0 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-base);
}
.header-section__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-section__welcome {
  margin-top: 12px;
}
</style>
