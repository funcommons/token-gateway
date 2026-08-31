<script setup lang="ts">
/**
 * FcThemeProvider - 主题/品牌 Provider (SDK).
 *
 * 替代业务侧 store/preference.ts 的 setAttribute + localStorage 逻辑.
 * 接收 brand / theme props, 同步 setAttribute 到 <html>, 让
 * html[data-brand='xxx'][data-theme='yyy'] 选择器命中 (由
 * sdk/theme/brands/_*.scss 提供的 CSS 规则生效).
 *
 * 内置 localStorage 持久化 (persistKey 可配), 启动时自动恢复.
 *
 * 用法:
 *   <FcThemeProvider v-model:brand="brand" v-model:theme="theme">
 *     <App />
 *   </FcThemeProvider>
 *
 *   // OEM 集成: initialBrand/initialTheme 作为兜底默认 (localStorage 没存时用)
 *   <FcThemeProvider
 *     :initial-brand="oem.config.brand"
 *     :initial-theme="oem.config.theme"
 *     v-model:brand="brand"
 *     v-model:theme="theme"
 *   >
 *     <App />
 *   </FcThemeProvider>
 *
 *   // 最简: 不传 props, 用内置默认 + 持久化
 *   <FcThemeProvider>
 *     <App />
 *   </FcThemeProvider>
 */
defineOptions({ name: 'FcThemeProvider', inheritAttrs: false })
import { computed, onMounted, onBeforeUnmount, provide, ref, watch } from 'vue'
import { isValidBrand, type ThemeMode } from './brands'

const STORAGE_VERSION = 1

interface Props {
  /** 当前 brand id (v-model). 不传则用内置持久化状态. */
  brand?: string
  /** 当前 theme (v-model). 不传则用内置持久化状态. */
  theme?: ThemeMode
  /** OEM 默认 brand (localStorage 没存时兜底). */
  initialBrand?: string
  /** OEM 默认 theme (localStorage 没存时兜底). */
  initialTheme?: ThemeMode
  /** localStorage key. 默认 'fc-theme-provider'. */
  persistKey?: string
  /** 是否持久化到 localStorage. 默认 true. */
  persist?: boolean
  /** 默认 brand (无 initialBrand 且 localStorage 没存时). */
  defaultBrand?: string
  /** 默认 theme (无 initialTheme 且 localStorage 没存时). */
  defaultTheme?: ThemeMode
}

const props = withDefaults(defineProps<Props>(), {
  persistKey: 'fc-theme-provider',
  persist: true,
  defaultBrand: 'ldx2',
  defaultTheme: 'light',
})

const emit = defineEmits<{
  'update:brand': [brand: string]
  'update:theme': [theme: ThemeMode]
  change: [payload: { brand: string, theme: ThemeMode }]
}>()

// 内部状态 (props.brand/theme 未传时用)
const innerBrand = ref<string>(props.defaultBrand)
const innerTheme = ref<ThemeMode>(props.defaultTheme)

// 实际生效值: props 优先, 否则用 inner
const currentBrand = computed(() => props.brand ?? innerBrand.value)
const currentTheme = computed(() => props.theme ?? innerTheme.value)

// === localStorage ===
interface StoredState {
  v: number
  brand: string
  theme: ThemeMode
}

function loadPersisted(): StoredState | null {
  if (!props.persist) return null
  try {
    const raw = localStorage.getItem(props.persistKey)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StoredState
    if (parsed.v !== STORAGE_VERSION) return null
    return parsed
  } catch {
    return null
  }
}

function savePersisted() {
  if (!props.persist) return
  const state: StoredState = {
    v: STORAGE_VERSION,
    brand: currentBrand.value,
    theme: currentTheme.value,
  }
  try {
    localStorage.setItem(props.persistKey, JSON.stringify(state))
  } catch {
    // private mode / quota - ignore
  }
}

// === DOM 写入 ===
function applyToRoot() {
  const html = document.documentElement
  const brand = isValidBrand(currentBrand.value) ? currentBrand.value : props.defaultBrand
  const theme = currentTheme.value
  html.setAttribute('data-brand', brand)
  html.setAttribute('data-theme', theme)
}

// === 初始化 ===
function initState() {
  const persisted = loadPersisted()
  const initialB = props.initialBrand ?? props.defaultBrand
  const initialT = props.initialTheme ?? props.defaultTheme
  innerBrand.value = persisted?.brand ?? initialB
  innerTheme.value = persisted?.theme ?? initialT
}

// 初始化时同步执行 (避免首屏闪烁)
initState()
applyToRoot()

onMounted(() => {
  applyToRoot()
})

onBeforeUnmount(() => {
  // Provider 卸载时不清 DOM (适合 SPA 切换时短暂卸载场景)
})

// === watch ===
watch([currentBrand, currentTheme], () => {
  applyToRoot()
  savePersisted()
  emit('change', { brand: currentBrand.value, theme: currentTheme.value })
})

watch(() => props.brand, (v) => {
  if (v !== undefined) {
    innerBrand.value = v
    emit('update:brand', v)
  }
})

watch(() => props.theme, (v) => {
  if (v !== undefined) {
    innerTheme.value = v
    emit('update:theme', v)
  }
})

// === provide ===
provide('fc-theme', {
  brand: currentBrand,
  theme: currentTheme,
  setBrand(b: string) {
    if (!isValidBrand(b)) return
    innerBrand.value = b
    emit('update:brand', b)
  },
  setTheme(t: ThemeMode) {
    innerTheme.value = t
    emit('update:theme', t)
  },
})

// 暴露方法给业务 (允许非 v-model 调用)
defineExpose({
  applyToRoot,
  reset() {
    innerBrand.value = props.initialBrand ?? props.defaultBrand
    innerTheme.value = props.initialTheme ?? props.defaultTheme
    applyToRoot()
    savePersisted()
  },
  currentBrand,
  currentTheme,
})
</script>

<template>
  <slot />
</template>
