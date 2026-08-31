import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { i18n, setLocale as setI18nLocale } from '@/locales'
import router from '@/router'
import App from '@/App.vue'
import { usePreferenceStore } from '@/stores/preference'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(i18n)

// Apply stored preference as early as possible (theme/brand to <html>)
const pref = usePreferenceStore()
pref.init()
setI18nLocale(pref.locale)

app.mount('#app')
