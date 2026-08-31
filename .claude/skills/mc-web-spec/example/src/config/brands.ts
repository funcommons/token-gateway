// src/config/brands.ts
// Single source of truth for brand metadata (label, icon, description).
// Drives:
//   1. The appearance drawer's brand picker
//   2. The Brand TS type
//   3. Type-safe lookups anywhere a brand name is needed
//
// Adding a new brand requires:
//   - src/styles/brands/_<name>.scss with @include define-brand(...)
//   - Add an entry here
// That's it — the picker UI, TS types, and tests pick it up automatically.

export interface BrandMeta {
  label: string
  icon: string
  desc: string
}

export const BRAND_REGISTRY = {
  apple: {
    label: 'Apple HIG',
    icon: 'ri-apple-line',
    desc: 'HIG · 12px radius, SF Pro font, soft shadows',
  },
  microsoft: {
    label: 'Microsoft Fluent',
    icon: 'ri-windows-line',
    desc: 'Fluent · 4px radius, Segoe UI font, sharp shadows',
  },
  google: {
    label: 'Google Material',
    icon: 'ri-google-line',
    desc: 'Material 3 · 8px radius, Inter font, emphasized shadows',
  },
  ldx2: {
    label: 'Ldx2 AIGC',
    icon: 'ri-rocket-line',
    desc: 'Apple-inspired · orange accent, large radii, dark sidebar',
  },
  vonnex: {
    label: 'Vonnex AI',
    icon: 'ri-cpu-line',
    desc: 'AI Gateway · Material 3, blue accent, 8px grid, glass panels',
  },
  mchuan: {
    label: 'mchuan',
    icon: 'ri-leaf-line',
    desc: '现代 B 端后台 · 8px 圆角, 平面阴影, 蓝色 accent',
  },
  manyun: {
    label: '漫云 manyun',
    icon: 'ri-leaf-line',
    desc: '智慧农业 SaaS · 单色青绿系 (海洋绿 / 薄荷青 / 鼠尾草), 12px 卡 + 细描边',
  },
} as const satisfies Record<string, BrandMeta>

export type Brand = keyof typeof BRAND_REGISTRY
export const BRANDS: Brand[] = Object.keys(BRAND_REGISTRY) as Brand[]
