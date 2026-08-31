<script setup lang="ts">
defineOptions({ name: 'FcImage' })
/**
 * FcImage — img 替代品 (SDK).
 *
 * 解决项目里散落 <img class="cover" :src="url"> + 自写加载/失败/fallback 样式的痛点:
 *  - 加载中显示 shimmer 占位
 *  - 加载失败显示 fallback (默认占位图 或 name 首字母)
 *  - 比例锁 (ratio 锁定宽高比, 防止 layout shift)
 *  - 圆角统一 (radius token)
 *  - 懒加载默认开启
 *
 * 用法:
 *   <FcImage :src="url" :alt="name" ratio="1/1" radius="8" />
 *   <FcImage :src="url" :fallback="defaultCover" />
 *   <FcImage :src="user.avatar" :name="user.name" shape="circle" />
 */
import { computed, ref, onErrorCaptured } from 'vue'
import { ElImageViewer } from 'element-plus'

type FitMode = 'cover' | 'contain' | 'fill' | 'none' | 'scale-down'
type Shape = 'rect' | 'circle'

interface Props {
  /** 图片地址. */
  src?: string | null
  /** alt 文案. */
  alt?: string
  /** 备用图 (主图失败时). */
  fallback?: string
  /** 失败时显示的姓名 (取首字母), 与 fallback 互斥. */
  name?: string
  /** 显示模式. 默认 'cover'. */
  fit?: FitMode
  /** 形状. 'circle' 时强制 1:1. */
  shape?: Shape
  /** 比例锁, 如 '1/1' '16/9' '4/3'. 不传则不锁. */
  ratio?: string
  /** 圆角 (px 或 token 名). */
  radius?: string | number
  /** 宽度 (px). */
  width?: number | string
  /** 高度 (px). 不传时由 ratio 推导. */
  height?: number | string
  /** 懒加载. 默认 true. */
  lazy?: boolean
  /** 失败时是否重试 (默认 false). */
  retry?: boolean
  /** 预览图列表 (传值后点击图片打开全屏 viewer, 替代 el-image preview-src-list). */
  previewSrcList?: string[]
  /** 预览初始索引. */
  previewInitialIndex?: number
  /** 是否可点击预览 (previewSrcList 有值时生效). 默认 true. */
  previewable?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  fit: 'cover',
  shape: 'rect',
  lazy: true,
  retry: false,
  previewInitialIndex: 0,
  previewable: true,
})

const emit = defineEmits<{
  load: [event: Event]
  error: [event: Event]
}>()

const status = ref<'loading' | 'loaded' | 'error'>('loading')
const attempt = ref(0)

const actualSrc = computed(() => props.src || props.fallback || '')
const initial = computed(() => {
  if (!props.name) return ''
  return props.name.trim().charAt(0).toUpperCase()
})
const aspectRatio = computed(() => props.ratio || (props.shape === 'circle' ? '1/1' : undefined))

const radiusValue = computed(() => {
  if (props.radius === undefined) return undefined
  if (typeof props.radius === 'number') return `${props.radius}px`
  if (/^\d+$/.test(props.radius)) return `${props.radius}px`
  return `var(--app-radius-${props.radius}, ${props.radius})`
})

const styles = computed(() => ({
  width: typeof props.width === 'number' ? `${props.width}px` : props.width,
  height: typeof props.height === 'number' ? `${props.height}px` : props.height,
  aspectRatio: aspectRatio.value,
  borderRadius: props.shape === 'circle' ? '50%' : radiusValue.value,
}))

function onLoad(e: Event) {
  status.value = 'loaded'
  emit('load', e)
}

function onError(e: Event) {
  if (props.retry && attempt.value < 2 && actualSrc.value) {
    attempt.value++
    return
  }
  status.value = 'error'
  emit('error', e)
}

const previewVisible = ref(false)
const canPreview = computed(() =>
  props.previewable && props.previewSrcList && props.previewSrcList.length > 0
)
function openPreview() {
  if (canPreview.value) previewVisible.value = true
}

onErrorCaptured(() => false)
</script>

<template>
  <div
    class="fc-image"
    :class="[`shape-${shape}`, `status-${status}`, { 'is-previewable': canPreview }]"
    :style="styles"
    @click="openPreview"
  >
    <img
      v-if="actualSrc && status !== 'error'"
      :src="actualSrc"
      :alt="alt || name || ''"
      :loading="lazy ? 'lazy' : 'eager'"
      :style="{ objectFit: fit }"
      @load="onLoad"
      @error="onError"
    />

    <!-- 失败 fallback: name 首字母 / fallback 图 -->
    <template v-else>
      <img v-if="fallback && !name" :src="fallback" :alt="alt || ''" :style="{ objectFit: fit }" />
      <span v-else-if="initial" class="fc-image__initial">{{ initial }}</span>
      <slot v-else name="fallback">
        <span class="fc-image__placeholder" />
      </slot>
    </template>

    <!-- 加载中 shimmer -->
    <span v-if="status === 'loading' && actualSrc" class="fc-image__shimmer" />
  </div>

  <!-- 全屏预览 viewer (替代 el-image preview-src-list) -->
  <el-image-viewer
    v-if="previewVisible"
    :url-list="previewSrcList || []"
    :initial-index="previewInitialIndex"
    teleported
    @close="previewVisible = false"
  />
</template>

<style scoped lang="scss">
.fc-image {
  position: relative;
  display: inline-block;
  overflow: hidden;
  background: var(--app-bg-muted, #f5f5f5);
  vertical-align: middle;

  &.is-previewable {
    cursor: zoom-in;
  }

  img {
    display: block;
    width: 100%;
    height: 100%;
  }

  &.shape-circle {
    border-radius: 50%;
  }
}

.fc-image__initial {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 40%;
  color: var(--app-on-primary, #fff);
  background: linear-gradient(135deg,
    color-mix(in srgb, var(--app-primary, #409eff) 70%, transparent),
    color-mix(in srgb, var(--app-primary, #409eff) 90%, transparent)
  );
}

.fc-image__placeholder {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(45deg, transparent 48%, var(--app-separator, #e5e5e5) 48%, var(--app-separator, #e5e5e5) 52%, transparent 52%),
    var(--app-bg-muted, #f5f5f5);
  opacity: 0.4;
}

.fc-image__shimmer {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    90deg,
    transparent 0%,
    color-mix(in srgb, var(--app-text, #fff) 8%, transparent) 50%,
    transparent 100%
  );
  animation: fc-image-shimmer 1.4s linear infinite;
  pointer-events: none;
}

@keyframes fc-image-shimmer {
  0%   { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}
</style>
