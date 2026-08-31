import type { Brand } from '@/config/brands'

export type Theme = 'light' | 'dark' | 'orange-black'
export type { Brand }
export type Locale = 'zh-CN' | 'en-US'

export interface Preference {
  theme: Theme
  brand: Brand
  locale: Locale
  sidebarCollapsed: boolean
  sidebarWidth: number
  showAppearance: boolean
}

export const THEME_LABELS: Record<Theme, string> = {
  light: '明色',
  dark: '暗色',
  'orange-black': '橙黑',
}

export const LOCALE_LABELS: Record<Locale, string> = {
  'zh-CN': '中文',
  'en-US': 'English',
}

export const THEME_ICONS: Record<Theme, string> = {
  light: 'ri-sun-line',
  dark: 'ri-moon-line',
  'orange-black': 'ri-fire-line',
}
