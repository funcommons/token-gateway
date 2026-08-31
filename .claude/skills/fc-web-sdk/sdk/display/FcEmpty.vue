<script setup lang="ts">
defineOptions({ name: 'FcEmpty' })
/**
 * FcEmpty — 通用空/错误/加载/无结果状态 (SDK).
 *
 * 取代 grid/EmptyState.vue. 区别:
 *  - 默认 5 种 type 预设图标 + i18n 文案 (empty / error / processing / search / no-result)
 *  - 支持 #icon slot 覆盖图标
 *  - 支持 #action slot 放按钮
 *  - 支持 title / description 双行文案覆盖
 *  - 适配 grid (grid-column: 1 / -1) 跟非 grid 容器
 *
 * 用法:
 *   <FcEmpty type="empty" />
 *   <FcEmpty type="search" :description="t('xx.no-match')">
 *     <template #action>
 *       <el-button @click="reset">{{ t('common.reset') }}</el-button>
 *     </template>
 *   </FcEmpty>
 *   <FcEmpty>
 *     <template #icon><i class="ri-image-line" /></template>
 *     <template #default>暂无图片</template>
 *   </FcEmpty>
 */
import { computed, useSlots } from 'vue'
import { useI18n } from 'vue-i18n'

type EmptyType = 'empty' | 'error' | 'processing' | 'search' | 'no-result'

interface Props {
  /** 预设类型, 决定默认图标 + 默认文案 i18n key. */
  type?: EmptyType
  /** 主文案. 不传走 i18n (`emptyState.<type>`). */
  title?: string
  /** 副文案. 不传则不显示. */
  description?: string
  /** 在 grid 父容器内是否占满整行. 默认 true. */
  spanFull?: boolean
  /** 是否展示处理中 spinner (type=processing 时自动 true). */
  spinning?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  type: 'empty',
  title: '',
  description: '',
  spanFull: true,
  spinning: undefined,
})

const slots = useSlots()
const { t, te } = useI18n()

const isSpinning = computed(() =>
  props.spinning ?? props.type === 'processing'
)

const resolvedTitle = computed(() => {
  if (props.title) return props.title
  const key = `emptyState.${props.type}`
  return te(key) ? t(key) : t('emptyState.empty')
})

const hasCustomIcon = computed(() => !!slots.icon)
</script>

<template>
  <div
    class="fc-empty"
    :class="[`type-${type}`, { 'is-span-full': spanFull, 'is-spinning': isSpinning }]"
    role="status"
  >
    <div v-if="!hasCustomIcon" class="fc-empty__icon">
      <svg v-if="type === 'empty'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      <svg v-else-if="type === 'error'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <circle cx="12" cy="12" r="10"/>
        <path d="M12 8v4m0 4h.01" stroke-linecap="round"/>
      </svg>
      <svg v-else-if="type === 'search'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <circle cx="11" cy="11" r="8"/>
        <path d="m21 21-4.35-4.35" stroke-linecap="round"/>
      </svg>
      <svg v-else-if="type === 'no-result'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <circle cx="11" cy="11" r="8"/>
        <path d="m21 21-4.35-4.35M8 8l6 6M14 8l-6 6" stroke-linecap="round"/>
      </svg>
      <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
    </div>
    <div v-else class="fc-empty__icon">
      <slot name="icon" />
    </div>

    <p v-if="!$slots.default" class="fc-empty__title">{{ resolvedTitle }}</p>
    <p v-else class="fc-empty__title"><slot /></p>

    <p v-if="description" class="fc-empty__desc">{{ description }}</p>

    <div v-if="$slots.action" class="fc-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.fc-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-2xl, 32px);
  text-align: center;
  color: var(--app-text-secondary);

  &.is-span-full {
    grid-column: 1 / -1;
  }
}

.fc-empty__icon {
  width: 64px;
  height: 64px;
  color: var(--app-text-quaternary);
  margin-bottom: var(--space-md, 12px);

  svg,
  :deep(svg),
  i {
    width: 100%;
    height: 100%;
    display: block;
  }

  .is-spinning & {
    animation: fc-empty-spin 1s linear infinite;
  }
}

.fc-empty__title {
  margin: 0;
  font-size: var(--app-font-size-base, 14px);
  font-weight: 500;
  color: var(--app-text-secondary);
}

.fc-empty__desc {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--app-text-tertiary);
  max-width: 320px;
}

.fc-empty__action {
  margin-top: var(--space-md, 12px);
  display: flex;
  gap: 8px;
  align-items: center;
}

@keyframes fc-empty-spin {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}
</style>
