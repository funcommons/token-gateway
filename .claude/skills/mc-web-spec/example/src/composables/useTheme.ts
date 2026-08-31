// src/composables/useTheme.ts
import { computed } from 'vue'
import { usePreferenceStore } from '@/stores/preference'
import type { Theme } from '@/types/preference'

export function useTheme() {
  const store = usePreferenceStore()
  const theme = computed<Theme>(() => store.theme)
  function setTheme(t: Theme) {
    store.setTheme(t)
  }
  return { theme, setTheme }
}
