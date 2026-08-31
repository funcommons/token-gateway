// src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes'
import { usePreferenceStore } from '@/stores/preference'

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(_to, _from, saved) {
    if (saved) return saved
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  const store = usePreferenceStore()
  const titleKey = to.meta.title as string | undefined
  if (titleKey) {
    // title is set in App.vue via i18n reactive watch; here we only set the document title prefix
    document.title = `${to.name?.toString().toUpperCase() ?? ''} · 前端开发规范`
  } else {
    document.title = '前端开发规范'
  }
  // ensures store is referenced; suppress unused
  void store
  return true
})

export default router
