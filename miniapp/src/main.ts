import '@/style/index.scss'
import '@/gb/gb.scss'
import 'virtual:uno.css'
import { createSSRApp } from 'vue'
import App from './App.vue'
import store from './store'

export function createApp() {
  const app = createSSRApp(App)
  app.use(store)
  return { app }
}
