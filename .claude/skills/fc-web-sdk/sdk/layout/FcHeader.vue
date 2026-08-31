<script setup lang="ts">defineOptions({ name: 'FcHeader' })
// FcHeader — 纯视觉壳 (SDK).
// 4 个 slot: brand / search / actions / user. 零 props 零 emits 零 store 零 i18n 零 router.
// 所有"内容"由 host (App.vue) 通过 slot 填入, 所有"行为"由 host 监听
// @click 等事件决定 (SDK 不发射事件, 业务侧的 button 自己绑).

// Hamburger 按钮在移动端自动显示, 点击 emit('toggle-sidebar') 让 host 决定
// (打开 el-drawer 侧栏 / 折叠态切换等).
import { useResponsive } from '@/composables'

const emit = defineEmits<{
  'toggle-sidebar': []
}>()

const { isMobile } = useResponsive()
</script>

<template>
  <header class="fc-header">
    <div class="fc-header__left">
      <el-button
        v-if="isMobile"
        text
        class="fc-header__hamburger"
        @click="emit('toggle-sidebar')"
      >
        <i class="ri-menu-line" />
      </el-button>
      <slot name="brand" />
    </div>

    <div class="fc-header__center">
      <slot name="search" />
    </div>

    <div class="fc-header__right">
      <slot name="actions" />
      <slot name="user" />
    </div>
  </header>
</template>

<style scoped>
.fc-header {
  height: var(--app-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--app-header-padding);
  gap: var(--app-header-gap);
  background: var(--app-header-bg);
  border-bottom: var(--app-header-border-width) solid var(--app-header-border-color);
  box-shadow: var(--app-header-shadow);
  backdrop-filter: var(--app-header-backdrop);
  -webkit-backdrop-filter: var(--app-header-backdrop);
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 1000;
}

.fc-header__left {
  display: flex;
  align-items: center;
  gap: var(--app-header-gap-left);
  flex-shrink: 0;
}

.fc-header__hamburger {
  width: var(--app-header-touch-target);
  height: var(--app-header-touch-target);
  padding: 0;
  font-size: var(--app-header-logo-size);
  font-weight: 700;
}
.fc-header__hamburger :deep(.ri-menu-line) {
  font-size: var(--app-header-logo-size);
  font-weight: 700;
}

.fc-header__center {
  flex: 1;
  max-width: var(--app-header-search-max-width);
}

.fc-header__right {
  display: flex;
  align-items: center;
  gap: var(--app-header-gap-right);
  flex-shrink: 0;
}

@media (max-width: 768px) {
  .fc-header__center {
    display: none;
  }
}
</style>
