<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePreferenceStore } from '@/stores/preference'
import { useResponsive } from '@/composables/useResponsive'
import { useLocale } from '@/composables/useLocale'
import { BRAND_REGISTRY, BRANDS } from '@/config/brands'
import { THEME_ICONS } from '@/types/preference'
import type { Theme, Brand, Locale } from '@/types/preference'

interface Props {
  // Optional so the v-model from App.vue can pass undefined initially
  // (before the user has clicked the appearance button) without tripping
  // Element Plus's Boolean type-check on the el-drawer modelValue prop.
  modelValue?: boolean
}
const props = withDefaults(defineProps<Props>(), { modelValue: false })
const emit = defineEmits<{ 'update:modelValue': [v: boolean] }>()

const { t } = useI18n()
const preference = usePreferenceStore()
const { isMobile } = useResponsive()
const { setLocale: setI18nLocale } = useLocale()

const themeLabels = computed(() => ({
  light: t('appearance.themeLight'),
  dark: t('appearance.themeDark'),
  'orange-black': t('appearance.themeOrange'),
}))

const themes = computed(() => [
  { value: 'light' as Theme, label: themeLabels.value.light, icon: THEME_ICONS.light, desc: t('appearance.descLight') },
  { value: 'dark' as Theme, label: themeLabels.value.dark, icon: THEME_ICONS.dark, desc: t('appearance.descDark') },
  { value: 'orange-black' as Theme, label: themeLabels.value['orange-black'], icon: THEME_ICONS['orange-black'], desc: t('appearance.descOrange') },
])

// Drive the brand picker from BRAND_REGISTRY — labels, icons, descriptions
// are all defined in one place (src/config/brands.ts) and overridable via
// i18n by passing the localized `descKey` into `t()`.
const brands = computed(() =>
  BRANDS.map((value) => {
    const meta = BRAND_REGISTRY[value]
    // descKey translates the static English desc into the current locale.
    // Falls back to the registry's English desc if the key is missing.
    const descKey = `appearance.desc${value.charAt(0).toUpperCase() + value.slice(1)}` as const
    return { value, label: meta.label, icon: meta.icon, desc: t(descKey, meta.desc) }
  }),
)

const locales = computed(() => [
  { value: 'zh-CN' as Locale, label: t('appearance.localeZh') },
  { value: 'en-US' as Locale, label: t('appearance.localeEn') },
])

function close() {
  emit('update:modelValue', false)
}

function setTheme(v: Theme) { preference.setTheme(v) }
function setBrand(v: Brand) { preference.setBrand(v) }
function setLocale(v: Locale) {
  preference.setLocale(v)
  setI18nLocale(v)
}
function setWidth(v: number) { preference.setSidebarWidth(v) }

// Re-sync i18n locale with the store whenever it changes (handles external changes)
watch(
  () => preference.locale,
  (v) => setI18nLocale(v),
  { immediate: true },
)
</script>

<template>
  <el-drawer
    :model-value="props.modelValue"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    direction="rtl"
    :size="isMobile ? '100%' : '420px'"
    :with-header="false"
  >
    <div class="settings-drawer">
      <header class="settings-drawer__header">
        <h2 class="settings-drawer__title">
          <i class="ri-palette-line" />
          {{ t('appearance.title') }}
        </h2>
        <el-button text size="large" @click="close">
          <i class="ri-close-line" />
        </el-button>
      </header>

      <div class="settings-drawer__body">
        <!-- Theme -->
        <section class="settings-section">
          <h3 class="settings-section__title">
            <i class="ri-palette-line" /> {{ t('appearance.sectionTheme') }}
          </h3>
          <p class="settings-section__desc">
            {{ t('appearance.current') }}:<strong>{{ themeLabels[preference.theme] }}</strong> ·
            {{ t(`appearance.desc${preference.theme === 'light' ? 'Light' : preference.theme === 'dark' ? 'Dark' : 'Orange'}`) }}
          </p>

          <div class="theme-preview">
            <div
              v-for="opt in [
                { v: 'light' as Theme, label: t('appearance.swatchLight') },
                { v: 'dark' as Theme, label: t('appearance.swatchDark') },
                { v: 'orange-black' as Theme, label: t('appearance.swatchOrange') },
              ]"
              :key="opt.v"
              class="theme-preview__swatch"
              :class="{ active: preference.theme === opt.v }"
              @click="setTheme(opt.v)"
            >
              <div :class="`swatch swatch--${opt.v === 'orange-black' ? 'orange' : opt.v}`" />
              <span>{{ opt.label }}</span>
            </div>
          </div>
        </section>

        <el-divider />

        <!-- Brand -->
        <section class="settings-section">
          <h3 class="settings-section__title">
            <i class="ri-shape-line" /> {{ t('appearance.sectionBrand') }}
          </h3>
          <p class="settings-section__desc">
            {{ t('appearance.current') }}:<strong>{{ BRAND_REGISTRY[preference.brand]?.label ?? '—' }}</strong> ·
            {{ brands.find((b) => b.value === preference.brand)?.desc }}
          </p>

          <div class="brand-preview">
            <div
              v-for="opt in brands"
              :key="opt.value"
              class="brand-card"
              :class="{ active: preference.brand === opt.value }"
              :title="opt.label"
              @click="setBrand(opt.value)"
            >
              <i :class="opt.icon" class="brand-card__icon" />
              <div class="brand-card__name">{{ opt.label }}</div>
            </div>
          </div>
        </section>

        <el-divider />

        <!-- Locale -->
        <section class="settings-section">
          <h3 class="settings-section__title">
            <i class="ri-translate-2" /> {{ t('appearance.sectionLocale') }}
          </h3>
          <p class="settings-section__desc">{{ t('appearance.descLocale') }}</p>
          <el-radio-group :model-value="preference.locale" @change="setLocale" size="large">
            <el-radio-button v-for="opt in locales" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </section>

        <el-divider />

        <!-- Sidebar width -->
        <section class="settings-section">
          <h3 class="settings-section__title">
            <i class="ri-side-bar-line" /> {{ t('appearance.sectionSidebarWidth') }}
          </h3>
          <p class="settings-section__desc">
            {{ t('appearance.descSidebarWidth', { width: preference.sidebarWidth, min: 200, max: 400 }) }}
          </p>
          <el-slider
            :model-value="preference.sidebarWidth"
            :min="200"
            :max="400"
            :step="10"
            show-input
            @input="setWidth"
            style="max-width: 360px"
          />
          <div class="app-row app-row--sm" style="margin-top: 8px">
            <el-button size="small" @click="setWidth(200)">{{ t('appearance.widthCompact', { value: 200 }) }}</el-button>
            <el-button size="small" @click="setWidth(240)">{{ t('appearance.widthDefault', { value: 240 }) }}</el-button>
            <el-button size="small" @click="setWidth(300)">{{ t('appearance.widthRoomy', { value: 300 }) }}</el-button>
            <el-button size="small" @click="setWidth(400)">{{ t('appearance.widthWide', { value: 400 }) }}</el-button>
          </div>
        </section>

        <el-divider />

        <p class="settings-drawer__footer">
          <i class="ri-information-line" /> {{ t('appearance.descFooter') }}
        </p>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
.settings-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--app-bg);
}
.settings-drawer__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
  background: var(--app-bg-elevated);
}
.settings-drawer__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--app-text);
  display: flex;
  align-items: center;
  gap: 8px;
}
.settings-drawer__title i {
  color: var(--app-primary);
}
.settings-drawer__body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 20px 24px;
}
.settings-section {
  padding: 16px 0 4px;
}
.settings-section__title {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
  display: flex;
  align-items: center;
  gap: 6px;
}
.settings-section__title i {
  color: var(--app-primary);
}
.settings-section__desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.5;
}
.settings-section__desc strong {
  color: var(--app-primary);
}
.settings-drawer__footer {
  font-size: 12px;
  color: var(--app-text-tertiary);
  text-align: center;
  margin: 16px 0 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
}

.theme-preview {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}
.theme-preview__swatch {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--app-text-secondary);
  cursor: pointer;
  transition: transform 0.15s;
}
.theme-preview__swatch:hover {
  transform: translateY(-2px);
}
.theme-preview__swatch.active {
  color: var(--app-primary);
  font-weight: 600;
}
.swatch {
  width: 60px;
  height: 40px;
  border-radius: var(--app-radius-md);
  border: 2px solid var(--el-border-color);
  position: relative;
  transition: border-color 0.2s, transform 0.2s;
}
.theme-preview__swatch.active .swatch {
  border-color: var(--app-primary);
  transform: scale(1.05);
}
.swatch--light {
  background: #ffffff;
}
.swatch--light::after {
  content: '';
  position: absolute;
  bottom: 4px; left: 4px; right: 4px; height: 4px;
  background: #409eff;
  border-radius: 2px;
}
.swatch--dark {
  background: #1a1a1a;
}
.swatch--dark::after {
  content: '';
  position: absolute;
  bottom: 4px; left: 4px; right: 4px; height: 4px;
  background: #409eff;
  border-radius: 2px;
}
.swatch--orange {
  background: #0e0e0e;
}
.swatch--orange::after {
  content: '';
  position: absolute;
  bottom: 4px; left: 4px; right: 4px; height: 4px;
  background: #ff7d00;
  border-radius: 2px;
}

.brand-preview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin-top: 12px;
}
.brand-card {
  padding: 8px 6px;
  border-radius: var(--app-radius-md);
  background: var(--el-bg-color);
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.15s, background 0.2s;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  min-width: 0;
  text-align: center;
}
.brand-card:hover {
  background: var(--el-color-primary-light-9);
}
.brand-card.active {
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 2px var(--app-primary);
}
.brand-card__icon {
  font-size: 16px;
  color: var(--app-primary);
  line-height: 1;
}
.brand-card__name {
  font-size: 11px;
  font-weight: 600;
  color: var(--app-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}
@media (max-width: 768px) {
  .brand-preview {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
