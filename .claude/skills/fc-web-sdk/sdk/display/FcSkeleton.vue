<script setup lang="ts">
defineOptions({ name: 'FcSkeleton' })
/**
 * FcSkeleton — 骨架屏原子 (SDK).
 *
 * variant:
 *  - text     : 单行/多行文本骨架 (rows 控制行数, 最后一行宽度 80%)
 *  - rect     : 自定义宽高的矩形 (用 width / height props)
 *  - avatar   : 圆形头像骨架 (size 控制直径, 默认 40px)
 *  - card     : 缩略图卡片骨架 (默认 16:9, 用 width / height 控制)
 *
 * animated: 默认 true (pulse), 关掉可做静态占位.
 *
 * 用法:
 *   <FcSkeleton variant="text" :rows="3" />
 *   <FcSkeleton variant="avatar" :size="48" />
 *   <FcSkeleton variant="card" :width="200" :height="120" />
 *   <FcSkeleton variant="rect" width="100%" height="32px" />
 */
import { computed, type CSSProperties } from 'vue'

type SkeletonVariant = 'text' | 'rect' | 'avatar' | 'card'

interface Props {
  variant?: SkeletonVariant
  /** text 模式行数, 默认 1. 其他模式忽略. */
  rows?: number
  /** rect/card 模式宽度. number=px, string=CSS. */
  width?: string | number
  /** rect/card 模式高度. number=px, string=CSS. */
  height?: string | number
  /** avatar 模式直径 (px). 默认 40. */
  size?: number
  /** 是否开 pulse 动画. 默认 true. */
  animated?: boolean
  /** 圆角 (px 或 CSS 字符串). 不传时: avatar=50%, text=4px, 其他=var(--app-radius-md). */
  radius?: string | number
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'text',
  rows: 1,
  width: '100%',
  height: 'auto',
  size: 40,
  animated: true,
  radius: undefined,
})

function toCss(v: string | number | undefined, fallback: string): string {
  if (v === undefined || v === null || v === '') return fallback
  return typeof v === 'number' ? `${v}px` : v
}

const wrapperStyle = computed<CSSProperties>(() => {
  switch (props.variant) {
    case 'avatar':
      return {
        width: `${props.size}px`,
        height: `${props.size}px`,
        borderRadius: toCss(props.radius, '50%'),
      }
    case 'rect':
      return {
        width: toCss(props.width, '100%'),
        height: toCss(props.height, '32px'),
        borderRadius: toCss(props.radius, 'var(--app-radius-md, 8px)'),
      }
    case 'card':
      return {
        width: toCss(props.width, '100%'),
        height: toCss(props.height, '120px'),
        borderRadius: toCss(props.radius, 'var(--app-radius-card, 12px)'),
      }
    case 'text':
    default:
      return {}
  }
})
</script>

<template>
  <div
    class="fc-skeleton"
    :class="[`variant-${variant}`, { 'is-animated': animated }]"
    role="status"
    aria-busy="true"
  >
    <!-- text 多行 -->
    <template v-if="variant === 'text'">
      <div
        v-for="i in rows"
        :key="i"
        class="fc-skeleton__line"
        :class="{ 'is-last': i === rows && rows > 1 }"
        :style="{ borderRadius: toCss(radius, '4px') }"
      />
    </template>

    <!-- 其他变体 -->
    <div v-else class="fc-skeleton__shape" :style="wrapperStyle" />
  </div>
</template>

<style scoped lang="scss">
.fc-skeleton {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.fc-skeleton__shape,
.fc-skeleton__line {
  background: var(--app-bg-muted, #f5f5f5);
  position: relative;
  overflow: hidden;
}

.fc-skeleton__line {
  height: 12px;
  width: 100%;

  &.is-last {
    width: 80%;
  }
}

.is-animated .fc-skeleton__shape,
.is-animated .fc-skeleton__line {
  &::after {
    content: '';
    position: absolute;
    inset: 0;
    transform: translateX(-100%);
    background: linear-gradient(
      90deg,
      transparent,
      color-mix(in srgb, var(--app-bg-card, #fff) 60%, transparent),
      transparent
    );
    animation: fc-skeleton-shimmer 1.4s infinite;
  }
}

@keyframes fc-skeleton-shimmer {
  100% {
    transform: translateX(100%);
  }
}
</style>
