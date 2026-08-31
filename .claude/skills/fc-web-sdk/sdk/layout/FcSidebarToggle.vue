<script setup lang="ts">
/**
 * FcSidebarToggle - 折叠/展开按钮 (SDK).
 *
 * 替代业务侧散落的 toggle button 样式 + hover 动画 (之前写在 _brands.scss mixin
 * brand-overrides 里, 现统一到 SDK 组件).
 *
 * 用法:
 *   <FcSidebarToggle
 *     :collapsed="collapsed"
 *     @click="onToggle"
 *     placement="header"   <!-- header 在 header 居右; footer 在 sidebar 顶部 -->
 *   />
 *
 * 内置 EP icon (Expand / Fold) + tooltip + hover 动画, 颜色走品牌 token.
 */
defineOptions({ name: 'FcSidebarToggle' })
import { Expand, Fold } from '@element-plus/icons-vue'

interface Props {
  /** 当前折叠态 (true = 折叠). */
  collapsed: boolean
  /** 按钮位置: header (top bar) / footer (sidebar 顶部) / inline (任意). */
  placement?: 'header' | 'footer' | 'inline'
  /** 是否禁用. */
  disabled?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  placement: 'header',
  disabled: false,
})

const emit = defineEmits<{
  click: []
}>()

function onClick() {
  if (props.disabled) return
  emit('click')
}
</script>

<template>
  <button
    type="button"
    class="fc-sidebar-toggle"
    :class="[
      `placement-${placement}`,
      { 'is-collapsed': collapsed, 'is-disabled': disabled },
    ]"
    :disabled="disabled"
    :aria-label="collapsed ? 'Expand sidebar' : 'Collapse sidebar'"
    :title="collapsed ? 'Expand sidebar' : 'Collapse sidebar'"
    @click="onClick"
  >
    <el-icon class="fc-sidebar-toggle__icon">
      <Fold v-if="!collapsed" />
      <Expand v-else />
    </el-icon>
  </button>
</template>

<style scoped lang="scss">
.fc-sidebar-toggle {
  appearance: none;
  border: none;
  background: transparent;
  padding: 6px;
  cursor: pointer;
  border-radius: var(--app-radius-sm, 4px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--brand-sidebar-text-regular, var(--app-text-secondary, #666));
  transition: background 0.15s ease, color 0.15s ease;

  &:hover:not(.is-disabled) {
    background: var(--brand-sidebar-hover-bg, var(--app-bg-muted, rgba(0, 0, 0, 0.05)));
    color: var(--brand-sidebar-text-main, var(--app-text, #333));
  }

  &:active:not(.is-disabled) {
    transform: scale(0.95);
  }

  &.is-disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
}

.fc-sidebar-toggle__icon {
  font-size: 18px;
  transition: transform 0.2s ease;

  .fc-sidebar-toggle.is-collapsed & {
    transform: rotate(180deg);
  }
}

/* Placement variants (只改布局, 颜色统一) */
.placement-header {
  /* 默认 inline, 业务自己定位 */
}

.placement-footer {
  display: flex;
  justify-content: flex-end;
  padding: 4px 8px;
  border-bottom: 1px solid var(--brand-separator, var(--app-separator, #e5e5e5));
  margin-bottom: 4px;
}

.placement-inline {
  /* inline, 无额外样式 */
}
</style>