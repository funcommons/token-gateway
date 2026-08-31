<script setup lang="ts">
// AppHeader — 纯视觉壳 (SDK).
// 4 个 slot: brand / search / actions / user. 零 props 零 emits 零 store 零 i18n 零 router.
// 所有"内容"由 host (App.vue) 通过 slot 填入, 所有"行为"由 host 监听
// @click 等事件决定 (SDK 不发射事件, 业务侧的 button 自己绑).

// Hamburger 按钮在移动端自动显示, 点击 emit('toggle-sidebar') 让 host 决定
// (打开 el-drawer 侧栏 / 折叠态切换等).
import { useResponsive } from '@/composables/useResponsive'

const emit = defineEmits<{
  'toggle-sidebar': []
}>()

const { isMobile } = useResponsive()
</script>

<template>
  <header class="app-header">
    <div class="app-header__left">
      <el-button
        v-if="isMobile"
        text
        class="app-header__hamburger"
        @click="emit('toggle-sidebar')"
      >
        <i class="ri-menu-line" />
      </el-button>
      <slot name="brand" />
    </div>

    <div class="app-header__center">
      <slot name="search" />
    </div>

    <div class="app-header__right">
      <slot name="actions" />
      <slot name="user" />
    </div>
  </header>
</template>

<style scoped>
/* ============================================================
   AppHeader 视觉壳 — 全部值来自 --app-header-* CSS 变量, 默认值 = 当前
   DEMO 设计. 品牌覆写在 src/styles/brands/_*.scss 中. 不可改的常量
   (z-index 1000) 见注释.
   ============================================================ */

.app-header {
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
  /* 规格 3.2.1: AppHeader 必须 z-index 1000, 高于 sidebar / drawer.
     故意保留字面量, 不可被任何主题覆写. */
  z-index: 1000;
}

.app-header__left {
  display: flex;
  align-items: center;
  gap: var(--app-header-gap-left);
  flex-shrink: 0;
}

.app-header__hamburger {
  width: var(--app-header-touch-target);
  height: var(--app-header-touch-target);
  padding: 0;
  font-size: var(--app-header-logo-size);
  font-weight: 700;
}
.app-header__hamburger :deep(.ri-menu-line) {
  font-size: var(--app-header-logo-size);
  font-weight: 700;
}

.app-header__center {
  flex: 1;
  max-width: var(--app-header-search-max-width);
}

.app-header__right {
  display: flex;
  align-items: center;
  gap: var(--app-header-gap-right);
  flex-shrink: 0;
}

/* 响应式: 768px 与 HEADER_TOKENS.MOBILE_BREAKPOINT_PX 同源, 改时同步 */
@media (max-width: 768px) {
  .app-header__center {
    display: none;
  }
}
</style>
