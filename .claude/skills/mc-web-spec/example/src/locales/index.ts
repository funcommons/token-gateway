// src/locales/index.ts
import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'
import type { Locale } from '@/types/preference'

export const SUPPORTED_LOCALES: Locale[] = ['zh-CN', 'en-US']

const messages = {
  'zh-CN': zhCN,
  'en-US': enUS,
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'zh-CN',
  fallbackLocale: 'en-US',
  messages,
  // Missing translation warnings are noisy during dev; we use tOr() in pages
  // to provide a sensible Chinese fallback for demo-only descriptions.
  // vue-i18n v9 prefers the new option names (missingWarn / fallbackWarn);
  // the old silent* names are deprecated but still work — keep both for
  // forward + backward compat.
  missingWarn: false,
  fallbackWarn: false,
  silentTranslationWarn: true,
  silentFallbackWarn: true,
  warnHtmlMessage: false,
})

export function setLocale(locale: Locale) {
  i18n.global.locale.value = locale
  document.documentElement.setAttribute('lang', locale)
}

/**
 * Translate a key, but if it doesn't exist in the current locale, return the
 * provided fallback text (in Chinese). Useful for demo-only descriptions where
 * we don't want to maintain 2 versions of every line.
 */
export function tOr(key: string, fallback: string): string {
  const msg = i18n.global.t(key) as unknown
  if (typeof msg === 'string' && msg && msg !== key) return msg
  return fallback
}
