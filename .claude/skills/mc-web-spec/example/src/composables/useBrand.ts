// src/composables/useBrand.ts
import { computed } from 'vue'
import { usePreferenceStore } from '@/stores/preference'
import type { Brand } from '@/types/preference'

export function useBrand() {
  const store = usePreferenceStore()
  const brand = computed<Brand>(() => store.brand)
  function setBrand(b: Brand) {
    store.setBrand(b)
  }
  return { brand, setBrand }
}
