<script setup lang="ts">
// RowCard — 一行卡 + 可选 代码查看器 (SDK).
//
// "查看代码" / "隐藏代码" 文案从硬编码中文改为 props, 业务侧用 i18n 传入.
// 类名 .row-card / .row-card__* (去掉 my-* 前缀).
import { computed, ref, useSlots } from 'vue'
import { useI18n } from 'vue-i18n'

interface Props {
  title?: string
  code?: string
  /** 触发器: "查看代码" 按钮文本 (默认 t('common.code', 'Code')) */
  showCodeText?: string
  /** 触发器: "隐藏代码" 按钮文本 (默认 t('common.hide', 'Hide')) */
  hideCodeText?: string
}
const props = withDefaults(defineProps<Props>(), {
  showCodeText: '',
  hideCodeText: '',
})

const { t } = useI18n()
const showCode = ref(false)
const slots = useSlots()
const hasHeaderSlot = !!slots.header

// 业务侧没传 showCodeText / hideCodeText 时, 用 i18n 兜底 ('Code' / 'Hide')
const computedShowText = computed(() => props.showCodeText || t('common.code', 'Code'))
const computedHideText = computed(() => props.hideCodeText || t('common.hide', 'Hide'))
</script>

<template>
  <div class="row-card">
    <div v-if="hasHeaderSlot" class="row-card__header row-card__header--custom">
      <slot name="header" />
      <el-button v-if="code" link size="small" @click="showCode = !showCode">
        <i :class="showCode ? 'ri-code-box-line' : 'ri-code-s-slash-line'" />
        <span style="margin-left: 4px">{{ showCode ? computedHideText : computedShowText }}</span>
      </el-button>
    </div>
    <div v-else-if="title" class="row-card__header">
      <span class="row-card__title">{{ title }}</span>
      <el-button v-if="code" link size="small" @click="showCode = !showCode">
        <i :class="showCode ? 'ri-code-box-line' : 'ri-code-s-slash-line'" />
        <span style="margin-left: 4px">{{ showCode ? computedHideText : computedShowText }}</span>
      </el-button>
    </div>
    <div class="row-card__preview">
      <slot />
    </div>
    <div v-if="code && showCode" class="row-card__code">
      <pre><code>{{ code }}</code></pre>
    </div>
  </div>
</template>

<style scoped>
.row-card {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-md);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.row-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.row-card__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--app-text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.row-card__preview {
  min-height: 40px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.row-card__code {
  background: var(--app-bg-muted);
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: var(--app-radius-sm);
  padding: 12px;
  overflow-x: auto;
}

.row-card__code pre {
  margin: 0;
  font-family: 'Fira Code', 'Menlo', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: var(--app-text);
  white-space: pre-wrap;
}
</style>
