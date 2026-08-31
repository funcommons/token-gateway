// src/composables/useLocale.ts
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { usePreferenceStore } from '@/stores/preference'
import { setLocale as setI18nLocale } from '@/locales'
import type { Locale } from '@/types/preference'

export function useLocale() {
  const { t, locale: i18nLocale } = useI18n()
  const store = usePreferenceStore()
  const locale = computed<Locale>(() => store.locale)
  function setLocale(l: Locale) {
    store.setLocale(l)
    setI18nLocale(l)
    i18nLocale.value = l
  }
  return { locale, setLocale, t }
}
