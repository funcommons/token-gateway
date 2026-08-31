// src/composables/useBrandLoading.ts
// Brand-aware Element Plus loading SVG. Each brand may ship its own
// spinner under src/assets/loadings/_loading_<brand>.svg; the active one
// is picked from `preference.brand` and falls back to `_loading_default.svg`
// (which mirrors EP's built-in spinner). All SVGs use `currentColor`, so
// the spinner color follows `--brand-primary` and adapts to theme/dark mode
// without needing separate files per mode.
//
// Usage (directive):
//   <script setup>
//   import { useBrandLoading } from '@/composables/useBrandLoading'
//   const { svg, svgViewBox } = useBrandLoading()
//   </script>
//   <template>
//     <div
//       v-loading="loading"
//       :element-loading-svg="svg"
//       :element-loading-svg-view-box="svgViewBox"
//     >...</div>
//   </template>
//
// Usage (service):
//   import { brandLoadingService } from '@/composables/useBrandLoading'
//   const instance = brandLoadingService({ target: '.my-region', text: 'Loading…' })
//   // ...later
//   instance.close()

import { computed, type ComputedRef } from 'vue'
import { ElLoading } from 'element-plus'
import type { LoadingOptions } from 'element-plus'
import { usePreferenceStore } from '@/stores/preference'

// Vite eager glob: all _loading_*.svg files inlined as raw strings at
// build time. Tree-shaken: only the actually-shipped files are bundled.
const rawSvgs = import.meta.glob('@/assets/loadings/_loading_*.svg', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>

// Build { brand: svgString } map by parsing the file basename.
const SVG_BY_BRAND: Record<string, string> = {}
for (const [path, content] of Object.entries(rawSvgs)) {
  const match = path.match(/_loading_(\w+)\.svg$/)
  if (match) SVG_BY_BRAND[match[1]] = content
}

const SVG_VIEW_BOX = '0 0 50 50'

function pickSvg(brand: string): string {
  return SVG_BY_BRAND[brand] ?? SVG_BY_BRAND.default ?? ''
}

/** Read-only view of the brand→svg map; useful for demo / docs pages that
 *  want to render each brand spinner side-by-side without flipping the
 *  global `preference.brand`. */
export const BRAND_LOADING_SVGS: Readonly<Record<string, string>> = SVG_BY_BRAND
export const BRAND_LOADING_VIEW_BOX = SVG_VIEW_BOX

export interface UseBrandLoadingReturn {
  svg: ComputedRef<string>
  svgViewBox: string
}

export function useBrandLoading(): UseBrandLoadingReturn {
  const preference = usePreferenceStore()
  const svg = computed(() => pickSvg(preference.brand))
  return { svg, svgViewBox: SVG_VIEW_BOX }
}

export function brandLoadingService(
  options: LoadingOptions & { brand?: string } = {},
): ReturnType<typeof ElLoading.service> {
  const preference = usePreferenceStore()
  const { brand, ...rest } = options
  return ElLoading.service({
    svg: pickSvg(brand ?? preference.brand),
    svgViewBox: SVG_VIEW_BOX,
    ...rest,
  })
}
