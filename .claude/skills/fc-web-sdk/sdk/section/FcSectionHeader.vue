<script setup lang="ts">defineOptions({ name: 'FcSectionHeader' })
import FcSection from '@/components/sdk/section/FcSection.vue'

interface Props {
  title: string
  subtitle?: string
  back?: boolean
}
withDefaults(defineProps<Props>(), { subtitle: '', back: false })

const emit = defineEmits<{
  back: []
}>()
</script>

<template>
  <FcSection no-header-border>
    <template #header>
      <div class="fc-section-header__row">
        <div class="fc-section-header__main">
          <button v-if="back" class="fc-section-header__back" @click="emit('back')">
            <i class="ri-arrow-left-s-line" />
          </button>
          <div>
            <h1 class="fc-section-header__title">{{ title }}</h1>
            <p v-if="subtitle" class="fc-section-header__subtitle">{{ subtitle }}</p>
          </div>
        </div>
        <div class="fc-section-header__actions">
          <slot name="actions" />
        </div>
      </div>
    </template>
    <div v-if="$slots.welcome" class="fc-section-header__welcome">
      <slot name="welcome" />
    </div>
  </FcSection>
</template>

<style scoped>
.fc-section-header__back {
  background: none;
  border: none;
  padding: 4px;
  cursor: pointer;
  color: var(--app-text);
  font-size: 28px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: color 0.2s;
  &:hover {
    color: var(--app-primary);
  }
}
.fc-section-header__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.fc-section-header__main {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fc-section-header__title {
  margin: 0;
  font-size: var(--app-font-size-header);
  font-weight: 600;
  color: var(--app-text);
}
.fc-section-header__subtitle {
  margin: 4px 0 0;
  color: var(--app-text-secondary);
  font-size: var(--app-font-size-base);
}
.fc-section-header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fc-section-header__welcome {
  margin-top: 12px;
}
</style>
