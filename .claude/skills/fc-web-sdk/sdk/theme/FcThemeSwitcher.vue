<script setup lang="ts">
/**
 * FcThemeSwitcher - 主题/品牌切换 UI (SDK).
 *
 * 替代业务侧 AppearanceDrawer.vue. 提供 brand swatch grid + theme swatch row,
 * 双向绑定 v-model:brand / v-model:theme.
 *
 * 三种 variant:
 *  - inline  : 直接展开 UI (适合放 settings 面板内)
 *  - popover : 弹出气泡 (适合顶栏按钮触发, 默认)
 *  - drawer  : 弹出抽屉 (移动端友好)
 *
 * 内置 zh/en i18n, 业务侧可通过 :t prop 覆盖文案.
 *
 * 用法:
 *   <FcThemeSwitcher v-model:brand="brand" v-model:theme="theme" />
 *   <FcThemeSwitcher v-model:brand="brand" v-model:theme="theme" variant="drawer" />
 *   <FcThemeSwitcher v-model:brand="brand" v-model:theme="theme" variant="inline" />
 */
defineOptions({ name: 'FcThemeSwitcher' })
import { computed, inject, ref } from 'vue'
import { FcButton, FcPopover, FcDrawer } from '@/components/sdk'
import { BRANDS, THEMES, type ThemeMode } from './brands'

interface ThemeContext {
  brand: import('vue').Ref<string>
  theme: import('vue').Ref<ThemeMode>
  setBrand: (b: string) => void
  setTheme: (t: ThemeMode) => void
}

interface Props {
  brand?: string
  theme?: ThemeMode
  variant?: 'inline' | 'popover' | 'drawer'
  /** 触发按钮文案 (popover/drawer 模式). */
  triggerText?: string
  /** 触发按钮图标 (EP icon). */
  triggerIcon?: unknown
  /** 是否显示 reset 按钮. */
  showReset?: boolean
  /** i18n 函数 (不传用内置 en/zh). */
  t?: (key: string, params?: Record<string, unknown>) => string
  /** popover/drawer 弹层标题. */
  title?: string
}

const props = withDefaults(defineProps<Props>(), {
  variant: 'popover',
  triggerText: '',
  showReset: false,
  title: '',
})

const emit = defineEmits<{
  'update:brand': [brand: string]
  'update:theme': [theme: ThemeMode]
  reset: []
}>()

// === 内置 i18n (en/zh) ===
const messages: Record<string, Record<string, string>> = {
  'zh-CN': {
    'theme.title': '主题',
    'theme.section.theme': '主题配色',
    'theme.section.brand': '品牌',
    'theme.section.locale': '语言',
    'theme.light': '浅色',
    'theme.dark': '深色',
    'theme.reset': '恢复默认',
    'theme.reset-desc': '重置主题和品牌到默认配置',
    'theme.trigger': '外观',
  },
  'en-US': {
    'theme.title': 'Theme',
    'theme.section.theme': 'Color Scheme',
    'theme.section.brand': 'Brand',
    'theme.section.locale': 'Language',
    'theme.light': 'Light',
    'theme.dark': 'Dark',
    'theme.reset': 'Reset',
    'theme.reset-desc': 'Reset theme and brand to defaults',
    'theme.trigger': 'Appearance',
  },
}

const locale = computed(() => {
  const lang = (typeof document !== 'undefined' && document.documentElement.lang) || 'zh-CN'
  return messages[lang] ?? messages['zh-CN']
})

function tt(key: string): string {
  if (props.t) return props.t(key)
  return locale.value?.[key] ?? key
}

// === 内部状态 ===
// 优先用 inject 的 FcThemeProvider context (跟 Provider 同源, 切换会触发 Provider 的 watch -> applyToRoot + 持久化)
// 没注入 context 时, 退到 props.brand/theme + emit (兼容独立使用模式)
const themeContext = inject<ThemeContext | null>('fc-theme', null)

const innerBrand = computed<string>(() => themeContext?.brand.value ?? props.brand ?? 'ldx2')
const innerTheme = computed<ThemeMode>(() => themeContext?.theme.value ?? props.theme ?? 'light')

// drawer 模式 open 状态 (内部管理)
const drawerOpen = ref(false)

function pickBrand(id: string) {
  if (themeContext) {
    themeContext.setBrand(id)  // 触发 Provider 内部 watch -> applyToRoot + 持久化
  } else {
    emit('update:brand', id)   // 独立模式: 让外部 v-model 同步
  }
}

function pickTheme(id: ThemeMode) {
  if (themeContext) {
    themeContext.setTheme(id)
  } else {
    emit('update:theme', id)
  }
}

function reset() {
  emit('reset')
}

// === UI 内容 (inline/popover/drawer 共享) ===
// 用 functional 方式渲染, 避免重复
</script>

<template>
  <!-- inline: 直接渲染 -->
  <div v-if="variant === 'inline'" class="fc-theme-switcher fc-theme-switcher--inline">
    <section class="fc-ts-section">
      <h4 class="fc-ts-section__title">{{ tt('theme.section.theme') }}</h4>
      <div class="fc-ts-swatch-row">
        <button
          v-for="th in THEMES"
          :key="th.id"
          type="button"
          class="fc-ts-swatch"
          :class="{ active: innerTheme === th.id }"
          :style="{ background: th.bg, color: th.fg }"
          @click="pickTheme(th.id)"
        >
          <span class="fc-ts-swatch__label">{{ th.id === 'light' ? tt('theme.light') : tt('theme.dark') }}</span>
          <span v-if="innerTheme === th.id" class="fc-ts-swatch__check">
            <i class="ri-check-line" />
          </span>
        </button>
      </div>
    </section>

    <section class="fc-ts-section">
      <h4 class="fc-ts-section__title">{{ tt('theme.section.brand') }}</h4>
      <div class="fc-ts-brand-grid">
        <button
          v-for="b in BRANDS"
          :key="b.id"
          type="button"
          class="fc-ts-brand-card"
          :class="{ active: innerBrand === b.id }"
          @click="pickBrand(b.id)"
        >
          <span class="fc-ts-brand-card__swatch" :style="{ background: b.accent }"></span>
          <span class="fc-ts-brand-card__name">{{ b.label }}</span>
          <span class="fc-ts-brand-card__desc">{{ b.desc }}</span>
          <i v-if="innerBrand === b.id" class="ri-check-line fc-ts-brand-card__check" />
        </button>
      </div>
    </section>

    <section v-if="showReset" class="fc-ts-section fc-ts-section--reset">
      <FcButton variant="danger" block @click="reset">{{ tt('theme.reset') }}</FcButton>
    </section>
  </div>

  <!-- popover: 弹出气泡 -->
  <FcPopover
    v-else-if="variant === 'popover'"
    :width="320"
    placement="bottom-end"
    trigger="click"
    :show-arrow="false"
  >
    <template #trigger>
      <FcButton variant="secondary" :icon="triggerIcon">
        {{ triggerText || tt('theme.trigger') }}
      </FcButton>
    </template>
    <div class="fc-theme-switcher fc-theme-switcher--popover">
      <section class="fc-ts-section">
        <h4 class="fc-ts-section__title">{{ tt('theme.section.theme') }}</h4>
        <div class="fc-ts-swatch-row">
          <button
            v-for="th in THEMES"
            :key="th.id"
            type="button"
            class="fc-ts-swatch"
            :class="{ active: innerTheme === th.id }"
            :style="{ background: th.bg, color: th.fg }"
            @click="pickTheme(th.id)"
          >
            <span class="fc-ts-swatch__label">{{ th.id === 'light' ? tt('theme.light') : tt('theme.dark') }}</span>
            <span v-if="innerTheme === th.id" class="fc-ts-swatch__check">
              <i class="ri-check-line" />
            </span>
          </button>
        </div>
      </section>
      <section class="fc-ts-section">
        <h4 class="fc-ts-section__title">{{ tt('theme.section.brand') }}</h4>
        <div class="fc-ts-brand-grid">
          <button
            v-for="b in BRANDS"
            :key="b.id"
            type="button"
            class="fc-ts-brand-card"
            :class="{ active: innerBrand === b.id }"
            @click="pickBrand(b.id)"
          >
            <span class="fc-ts-brand-card__swatch" :style="{ background: b.accent }"></span>
            <span class="fc-ts-brand-card__name">{{ b.label }}</span>
            <span class="fc-ts-brand-card__desc">{{ b.desc }}</span>
            <i v-if="innerBrand === b.id" class="ri-check-line fc-ts-brand-card__check" />
          </button>
        </div>
      </section>
      <section v-if="showReset" class="fc-ts-section">
        <FcButton variant="danger" block size="sm" @click="reset">{{ tt('theme.reset') }}</FcButton>
      </section>
    </div>
  </FcPopover>

  <!-- drawer: 弹出抽屉 -->
  <template v-else>
    <FcButton variant="secondary" :icon="triggerIcon" @click="drawerOpen = true">
      {{ triggerText || tt('theme.trigger') }}
    </FcButton>
    <FcDrawer
      v-model:open="drawerOpen"
      :title="title || tt('theme.title')"
      direction="rtl"
      :size="380"
    >
      <div class="fc-theme-switcher fc-theme-switcher--drawer">
        <section class="fc-ts-section">
          <h4 class="fc-ts-section__title">{{ tt('theme.section.theme') }}</h4>
          <div class="fc-ts-swatch-row">
            <button
              v-for="th in THEMES"
              :key="th.id"
              type="button"
              class="fc-ts-swatch"
              :class="{ active: innerTheme === th.id }"
              :style="{ background: th.bg, color: th.fg }"
              @click="pickTheme(th.id)"
            >
              <span class="fc-ts-swatch__label">{{ th.id === 'light' ? tt('theme.light') : tt('theme.dark') }}</span>
              <span v-if="innerTheme === th.id" class="fc-ts-swatch__check">
                <i class="ri-check-line" />
              </span>
            </button>
          </div>
        </section>
        <section class="fc-ts-section">
          <h4 class="fc-ts-section__title">{{ tt('theme.section.brand') }}</h4>
          <div class="fc-ts-brand-grid">
            <button
              v-for="b in BRANDS"
              :key="b.id"
              type="button"
              class="fc-ts-brand-card"
              :class="{ active: innerBrand === b.id }"
              @click="pickBrand(b.id)"
            >
              <span class="fc-ts-brand-card__swatch" :style="{ background: b.accent }"></span>
              <span class="fc-ts-brand-card__name">{{ b.label }}</span>
              <span class="fc-ts-brand-card__desc">{{ b.desc }}</span>
              <i v-if="innerBrand === b.id" class="ri-check-line fc-ts-brand-card__check" />
            </button>
          </div>
        </section>
        <section v-if="showReset" class="fc-ts-section">
          <FcButton variant="danger" block @click="reset">{{ tt('theme.reset') }}</FcButton>
        </section>
      </div>
    </FcDrawer>
  </template>
</template>

<style scoped lang="scss">
.fc-theme-switcher {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 12px;
}

.fc-ts-section {
  display: flex;
  flex-direction: column;
  gap: 8px;

  &--reset {
    margin-top: 8px;
  }
}

.fc-ts-section__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text, #333);
  margin: 0;
}

.fc-ts-swatch-row {
  display: flex;
  gap: 8px;
}

.fc-ts-swatch {
  flex: 1;
  position: relative;
  height: 56px;
  border: 2px solid var(--app-separator, #e5e5e5);
  border-radius: var(--app-radius-md, 8px);
  cursor: pointer;
  overflow: hidden;
  transition: border-color 0.15s ease, transform 0.1s ease;

  &:hover { border-color: var(--app-primary, #409eff); }
  &.active { border-color: var(--app-primary, #409eff); box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #409eff) 25%, transparent); }
  &:active { transform: scale(0.97); }
}

.fc-ts-swatch__label {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
}

.fc-ts-swatch__check {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--app-primary, #409eff);
  color: #fff;
  font-size: 12px;
}

.fc-ts-brand-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}

.fc-ts-brand-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  padding: 10px;
  background: var(--app-bg-card, #fff);
  border: 1px solid var(--app-separator, #e5e5e5);
  border-radius: var(--app-radius-md, 8px);
  cursor: pointer;
  position: relative;
  transition: border-color 0.15s ease, transform 0.1s ease;

  &:hover { border-color: var(--app-primary, #409eff); }
  &.active { border-color: var(--app-primary, #409eff); box-shadow: 0 0 0 2px color-mix(in srgb, var(--app-primary, #409eff) 25%, transparent); }
  &:active { transform: scale(0.97); }
}

.fc-ts-brand-card__swatch {
  width: 100%;
  height: 24px;
  border-radius: var(--app-radius-sm, 4px);
  margin-bottom: 2px;
}

.fc-ts-brand-card__name {
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text, #333);
}

.fc-ts-brand-card__desc {
  font-size: 11px;
  color: var(--app-text-secondary, #999);
  line-height: 1.3;
}

.fc-ts-brand-card__check {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--app-primary, #409eff);
  color: #fff;
  font-size: 12px;
}
</style>
