import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

export const useThemeStore = defineStore('theme', () => {
  const theme = ref('dark')
  const isDark = computed(() => theme.value === 'dark')

  function initTheme() {
    const saved = localStorage.getItem('theme')
    if (saved === 'light' || saved === 'dark') {
      theme.value = saved
    } else if (window.matchMedia('(prefers-color-scheme: light)').matches) {
      theme.value = 'light'
    }
    applyTheme()
  }

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
    localStorage.setItem('theme', theme.value)
    applyTheme()
  }

  function applyTheme() {
    const root = document.documentElement
    if (theme.value === 'dark') {
      root.classList.add('dark')
      root.classList.remove('light')
    } else {
      root.classList.add('light')
      root.classList.remove('dark')
    }
  }

  return { theme, isDark, initTheme, toggleTheme }
})
