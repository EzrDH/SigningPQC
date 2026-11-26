import '@fontsource/plus-jakarta-sans/400.css'
import '@fontsource/plus-jakarta-sans/500.css'
import '@fontsource/plus-jakarta-sans/600.css'
import '@fontsource/plus-jakarta-sans/700.css'
import './style.css'

import { createPinia } from 'pinia'
import { createApp } from 'vue'
import App from './App.vue'
import { configureApi } from './api/client'
import { router } from './router'
import { useAuth } from './stores/auth'

const app = createApp(App)
app.use(createPinia())
const auth = useAuth()
configureApi({
  getToken: () => auth.token,
  onUnauthorized: () => {
    auth.clear()
    router.replace('/masuk')
  },
})
app.use(router)
app.mount('#app')
