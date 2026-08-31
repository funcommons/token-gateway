<template>
  <div
    class="fc-avatar"
    :class="`size-${size}`"
    :style="{ background: bgColor }"
    :title="name"
  >
    <img v-if="src" loading="lazy" :src="finalSrc" :alt="name" />
    <span v-else class="fc-avatar-char">{{ firstChar }}</span>
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'FcAvatar' })
import { computed } from 'vue'

interface Props {
  src?: string
  name: string
  size?: 'tiny' | 'small' | 'medium' | 'large'
  /** 可选 URL 转换器 (如 CDN 缩图). 接收 (src, size) 返回最终 URL. 不传则原样用 src. */
  urlTransform?: (src: string, size: string) => string
}

const props = withDefaults(defineProps<Props>(), {
  src: '',
  name: '',
  size: 'small',
  urlTransform: undefined,
})

const finalSrc = computed(() =>
  props.urlTransform ? props.urlTransform(props.src, props.size) : props.src
)

const firstChar = computed(() => {
  if (!props.name || props.name.trim() === '') return '?'
  return props.name.trim().charAt(0).toUpperCase()
})

const bgColor = computed(() => {
  if (!props.name || props.name.trim() === '') {
    return 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)'
  }
  const lastChar = props.name.slice(-1)
  const code = lastChar.charCodeAt(0)
  const angle = (code * 7) % 360
  const hue1 = angle
  const hue2 = (angle + 45) % 360
  return `linear-gradient(${angle}deg, hsl(${hue1}, 65%, 55%) 0%, hsl(${hue2}, 55%, 45%) 100%)`
})
</script>

<style scoped lang="scss">
.fc-avatar {
  border-radius: 50%;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-weight: 600;
  text-shadow: var(--app-text-shadow, 0 1px 2px rgba(0, 0, 0, 0.2));
  flex-shrink: 0;

  &.size-tiny {
    width: 24px;
    height: 24px;
    font-size: 10px;
  }

  &.size-small {
    width: 32px;
    height: 32px;
    font-size: 12px;
  }

  &.size-medium {
    width: 40px;
    height: 40px;
    font-size: 14px;
  }

  &.size-large {
    width: 56px;
    height: 56px;
    font-size: 18px;
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .fc-avatar-char {
    text-transform: uppercase;
  }
}
</style>
