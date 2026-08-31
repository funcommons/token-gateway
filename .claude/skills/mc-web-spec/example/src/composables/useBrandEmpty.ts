// src/composables/useBrandEmpty.ts
// Brand-aware Element Plus empty-state SVG. Each brand may ship its own
// illustration under src/assets/empties/_empty_<brand>.svg; the active one
// is picked from `preference.brand` and falls back to `_empty_default.svg`.
//
// The empty SVGs are self-contained `<svg>` elements (full <svg>...</svg>,
// not bare inner content). The default ships the Element Plus el-empty
// original image (viewBox 0 0 79 86, uses --el-empty-fill-color-* tokens
// defined by Element Plus for the active theme). Per-brand variants
// (e.g. _empty_mchuan.svg) use a Tabler / Feather-style line icon with
// `currentColor` strokes — single color, no accent, fade into the page.
// Differentiate "no data" / "no records" / "not found" by the description
// TEXT, not the image.
//
// This matches the "low-contrast empty state with brand accent" pattern
// used by most modern admin UIs (EP / Antd / Material) — the SVG doesn't
// shout, but a small brand-colored detail keeps it on-theme.
//
// Usage:
//   <script setup>
//   import { useBrandEmpty } from '@/composables/useBrandEmpty'
//   const { svg, viewBox } = useBrandEmpty()
//   </script>
//   <template>
//     <el-empty description="暂无数据">
//       <template #image>
//         <div class="brand-empty-svg" v-html="`<svg viewBox='${viewBox}'>${svg}</svg>`" />
//       </template>
//     </el-empty>
//   </template>
//
//   <style scoped>
//   .brand-empty-svg { width: 120px; height: 120px; color: var(--app-text-tertiary); }
//   .brand-empty-svg svg { width: 100%; height: 100%; }
//   </style>

import { computed, type ComputedRef } from 'vue'
import { usePreferenceStore } from '@/stores/preference'

// Vite eager glob: all _empty_*.svg files inlined as raw strings at
// build time. Tree-shaken automatically.
const rawSvgs = import.meta.glob('@/assets/empties/_empty_*.svg', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>

// Build { brand: svgString } map by parsing the file basename.
const SVG_BY_BRAND: Record<string, string> = {}
for (const [path, content] of Object.entries(rawSvgs)) {
  const match = path.match(/_empty_(\w+)\.svg$/)
  if (match) SVG_BY_BRAND[match[1]] = content
}

// viewBox is informational only — the SVG files are self-contained
// `<svg>` elements with their own viewBox. Kept for spec/doc display
// (matches the Element Plus el-empty default, 0 0 79 86).
export const EMPTY_VIEW_BOX = '0 0 79 86'

export interface UseBrandEmptyReturn {
  svg: ComputedRef<string>
  viewBox: string
}

/** Reactive empty-state SVG for the active brand. Falls back to
 *  `_empty_default.svg` when the active brand has no override. */
export function useBrandEmpty(): UseBrandEmptyReturn {
  const preference = usePreferenceStore()
  const svg = computed(
    () => SVG_BY_BRAND[preference.brand] ?? SVG_BY_BRAND.default ?? '',
  )
  return { svg, viewBox: EMPTY_VIEW_BOX }
}

/** Read-only view of brand→svg map. Useful for spec/demo pages. */
export const BRAND_EMPTY_SVGS: Readonly<Record<string, string>> = SVG_BY_BRAND
