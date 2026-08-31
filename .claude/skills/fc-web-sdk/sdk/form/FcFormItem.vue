<script setup lang="ts">
defineOptions({ name: 'FcFormItem' })
/**
 * FcFormItem — el-form-item 薄封装 (SDK).
 *
 * 统一 label 字号/字重、必填星号位置、错误文案样式.
 * 业务侧无需再写 :deep(.el-form-item__label) 之类覆写.
 *
 * 用法:
 *   <FcFormItem label="姓名" required error="必填">
 *     <FcInput v-model="name" />
 *   </FcFormItem>
 *
 * 跟 el-form 校验配合:
 *   <el-form :model="form" :rules="rules">
 *     <FcFormItem prop="name" label="姓名">...</FcFormItem>
 *   </el-form>
 */
import { useAttrs } from 'vue'

interface Props {
  /** 标签文案. */
  label?: string
  /** 字段名 (el-form 校验用). */
  prop?: string
  /** 必填星号 (仅视觉, 校验仍由 el-form rules 控制). */
  required?: boolean
  /** 错误文案 (覆盖 el-form 注入的错误). */
  error?: string
  /** 标签宽度. */
  labelWidth?: string | number
  /** 内容区右对齐文案 (如单位、提示). */
  hint?: string
}

withDefaults(defineProps<Props>(), {
  required: false,
})

const attrs = useAttrs()
</script>

<template>
  <el-form-item
    v-bind="attrs"
    :prop="prop"
    :label="label"
    :required="required"
    :error="error"
    :label-width="labelWidth"
    class="fc-form-item"
  >
    <slot />
    <template v-if="$slots.label" #label><slot name="label" /></template>
    <template v-if="hint" #error>
      <slot name="error">{{ error }}</slot>
      <div class="fc-form-item__hint">{{ hint }}</div>
    </template>
    <template v-else-if="$slots.error" #error><slot name="error" /></template>
  </el-form-item>
</template>

<style scoped lang="scss">
.fc-form-item {
  :deep(.el-form-item__label) {
    font-size: 13px;
    font-weight: 500;
    color: var(--app-text, inherit);
  }

  :deep(.el-form-item__error) {
    font-size: 12px;
    padding-top: 2px;
  }
}

.fc-form-item__hint {
  font-size: 12px;
  color: var(--app-text-tertiary, #999);
  line-height: 1.4;
  padding-top: 2px;
}
</style>
