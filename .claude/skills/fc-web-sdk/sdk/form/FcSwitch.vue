<script setup lang="ts">
defineOptions({ name: 'FcSwitch', inheritAttrs: false })
/**
 * FcSwitch - 原生 switch 实现 (SDK).
 *
 * 不用 el-switch 薄封装, 因为 EP switch 内部 watch(modelValue) 直接设
 * input.value.checked, 在条件渲染切换时 input ref 未挂载会抛
 * "Cannot set properties of undefined (setting 'checked')".
 *
 * 自实现 toggle + a11y, 颜色走品牌 token, 兼容 EP 的 active/inactive value
 * 与文案.
 *
 * 用法:
 *   <FcSwitch v-model="enabled" />
 *   <FcSwitch v-model="opt" active-text="开" inactive-text="关" />
 *   <FcSwitch v-model="sync" loading />
 */
import { computed } from 'vue'

interface Props {
  modelValue: boolean | string | number | undefined
  /** 开启值. 默认 true. */
  activeValue?: boolean | string | number
  /** 关闭值. 默认 false. */
  inactiveValue?: boolean | string | number
  /** 开启文案. */
  activeText?: string
  /** 关闭文案. */
  inactiveText?: string
  /** 禁用. */
  disabled?: boolean
  /** 加载中. */
  loading?: boolean
  /** 尺寸. */
  size?: 'large' | 'default' | 'small'
  /** 内联文案 (写在 knob 内部). */
  inlinePrompt?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  activeValue: true,
  inactiveValue: false,
  disabled: false,
  loading: false,
  size: 'default',
  inlinePrompt: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: boolean | string | number]
  change: [value: boolean | string | number]
}>()

const isActive = computed(() => props.modelValue === props.activeValue)

const sizeMap = {
  large:   { w: 56, h: 28, knob: 22, font: 14 },
  default: { w: 44, h: 22, knob: 18, font: 12 },
  small:   { w: 36, h: 18, knob: 14, font: 11 },
} as const
const dims = computed(() => sizeMap[props.size] ?? sizeMap.default)

function toggle() {
  if (props.disabled || props.loading) return
  const next = isActive.value ? props.inactiveValue : props.activeValue
  emit('update:modelValue', next)
  emit('change', next)
}
</script>

<template>
  <button
    type="button"
    role="switch"
    :aria-checked="isActive"
    :disabled="disabled || loading"
    class="fc-switch"
    :class="[
      `size-${size}`,
      {
        'is-active': isActive,
        'is-disabled': disabled,
        'is-loading': loading,
        'has-text': !!activeText || !!inactiveText,
      },
    ]"
    :style="{
      '--fc-switch-w': `${dims.w}px`,
      '--fc-switch-h': `${dims.h}px`,
      '--fc-switch-knob': `${dims.knob}px`,
      '--fc-switch-font': `${dims.font}px`,
    }"
    @click="toggle"
  >
    <span v-if="inactiveText && !isActive" class="fc-switch__label fc-switch__label--inactive">
      {{ inactiveText }}
    </span>
    <span class="fc-switch__track">
      <span class="fc-switch__knob">
        <span v-if="loading" class="fc-switch__spinner" />
        <span v-else-if="inlinePrompt && isActive" class="fc-switch__inline">✓</span>
      </span>
    </span>
    <span v-if="activeText && isActive" class="fc-switch__label fc-switch__label--active">
      {{ activeText }}
    </span>
  </button>
</template>

<style scoped lang="scss">
.fc-switch {
  appearance: none;
  border: none;
  background: transparent;
  padding: 0;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fc-switch-font, 12px);
  line-height: 1;
  outline: none;
  vertical-align: middle;
  user-select: none;

  &:focus-visible .fc-switch__track {
    box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #409eff) 30%, transparent);
  }

  &.is-disabled,
  &.is-loading {
    cursor: not-allowed;
    opacity: 0.6;
  }
}

.fc-switch__track {
  display: inline-block;
  position: relative;
  width: var(--fc-switch-w, 44px);
  height: var(--fc-switch-h, 22px);
  border-radius: calc(var(--fc-switch-h, 22px) / 2);
  background: var(--app-separator, #dcdfe6);
  transition: background 0.2s ease;
}

.fc-switch__knob {
  position: absolute;
  top: 1px;
  left: 1px;
  width: var(--fc-switch-knob, 18px);
  height: var(--fc-switch-knob, 18px);
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
  transition: transform 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}

.fc-switch.is-active .fc-switch__track {
  background: var(--app-primary, #409eff);
}

.fc-switch.is-active .fc-switch__knob {
  transform: translateX(calc(var(--fc-switch-w, 44px) - var(--fc-switch-knob, 18px) - 2px));
}

.fc-switch__label {
  color: var(--app-text-secondary, #666);
  font-size: var(--fc-switch-font, 12px);

  &--active {
    color: var(--app-primary, #409eff);
  }
}

.fc-switch__spinner {
  width: 60%;
  height: 60%;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: fc-switch-spin 0.6s linear infinite;
}

.fc-switch__inline {
  font-size: 10px;
  color: var(--app-primary, #409eff);
  font-weight: 700;
}

.fc-switch.is-active .fc-switch__inline {
  color: #fff;
}

@keyframes fc-switch-spin {
  to { transform: rotate(360deg); }
}
</style>
