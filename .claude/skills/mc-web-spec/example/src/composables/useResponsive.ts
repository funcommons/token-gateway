// src/composables/useResponsive.ts
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

export type Viewport = 'mobile' | 'tablet' | 'desktop'

// 视口断点 (与 src/config/header-tokens.ts 同源, 改时同步)
export const BREAKPOINTS = {
  mobile: 0,
  tablet: 768,
  desktop: 1024,
} as const

function detectViewport(): Viewport {
  if (typeof window === 'undefined') return 'desktop'
  const w = window.innerWidth
  if (w >= BREAKPOINTS.desktop) return 'desktop'
  if (w >= BREAKPOINTS.tablet) return 'tablet'
  return 'mobile'
}

const viewport = ref<Viewport>(detectViewport())

let listenersAttached = false
const callbacks = new Set<(v: Viewport) => void>()

function onResize() {
  const v = detectViewport()
  if (v !== viewport.value) {
    viewport.value = v
    callbacks.forEach((cb) => cb(v))
  }
}

function ensureListeners() {
  if (listenersAttached || typeof window === 'undefined') return
  window.addEventListener('resize', onResize, { passive: true })
  listenersAttached = true
}

export function useResponsive() {
  ensureListeners()
  const isMobile = computed(() => viewport.value === 'mobile')
  const isTablet = computed(() => viewport.value === 'tablet')
  const isDesktop = computed(() => viewport.value === 'desktop')
  const isSmall = computed(() => viewport.value !== 'desktop')
  return { viewport, isMobile, isTablet, isDesktop, isSmall }
}

export function watchViewport(cb: (v: Viewport) => void) {
  ensureListeners()
  callbacks.add(cb)
  onBeforeUnmount(() => callbacks.delete(cb))
}
