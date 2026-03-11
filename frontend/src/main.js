import { createApp } from 'vue'
import { createPinia } from 'pinia'
import './style.css'
import App from './App.vue'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)

// Initialize theme before mount to prevent flash
import { useThemeStore } from './stores/themeStore'
const themeStore = useThemeStore()
themeStore.initTheme()

app.mount('#app')
