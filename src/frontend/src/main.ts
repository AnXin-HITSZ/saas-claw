import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import './styles/main.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 401 时统一跳登录（http 层不直接依赖 router）
setUnauthorizedHandler(() => {
  router.push({ name: 'login' })
})

app.mount('#app')
