// sdk/theme/brands.ts
// Brand UI metadata (label/accent/desc). Token 仍由 brands/_*.scss 提供.
// FcThemeSwitcher 用这个数据渲染 brand swatch grid.

export interface BrandMeta {
  id: string
  label: string
  desc: string
  accent: string
}

export const BRANDS: BrandMeta[] = [
  { id: 'ldx2',     label: 'AIGC',     accent: '#ff6b00', desc: 'Apple-inspired 大圆角 + 橙色主调' },
  { id: 'apple',    label: 'Apple',     accent: '#007aff', desc: 'Apple HIG 风格, 系统蓝色' },
  { id: 'google',  label: 'Google',   accent: '#6750a4', desc: 'Material Design 紫色' },
  { id: 'mchuan',  label: 'B-End',    accent: '#2563eb', desc: 'B 端工程化蓝色' },
  { id: 'manyun',  label: 'Teal',     accent: '#2E8B57', desc: '海洋青绿, KPI 三档色' },
  { id: 'acme',    label: 'Acme',     accent: '#0070e0', desc: 'Acme Blue 工程化基调' },
  { id: 'microsoft', label: 'Microsoft', accent: '#0078d4', desc: 'Fluent Design 微软蓝' },
  { id: 'vonnex',  label: 'Vonnex',   accent: '#00a86b', desc: 'Vonnex 绿色, glass 效果' },
]

export type ThemeMode = 'light' | 'dark'

export interface ThemeMeta {
  id: ThemeMode
  label: string
  bg: string
  fg: string
}

export const THEMES: ThemeMeta[] = [
  { id: 'light', label: 'Light', bg: '#ffffff', fg: '#1f1f1f' },
  { id: 'dark',  label: 'Dark',  bg: '#1a1a1a', fg: '#e8e8e8' },
]

export function getBrand(id: string): BrandMeta | undefined {
  return BRANDS.find((b) => b.id === id)
}

export function isValidBrand(id: string): boolean {
  return BRANDS.some((b) => b.id === id)
}
