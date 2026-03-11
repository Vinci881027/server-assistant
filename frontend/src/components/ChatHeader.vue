<script setup>
import { useThemeStore } from '../stores/themeStore'

const themeStore = useThemeStore()

defineProps({
  ip: {
    type: String,
    default: '0.0.0.0'
  },
  isOnline: {
    type: Boolean,
    default: true
  }
});
defineEmits(['toggle-sidebar']);
</script>

<template>
  <header class="px-5 py-4 flex justify-between items-center border-b z-50"
          style="background-color: var(--bg-primary); border-color: var(--border-primary);">
    <div class="flex items-center gap-2">
      <button @click="$emit('toggle-sidebar')" class="header-sidebar-toggle mr-1 p-2 rounded-lg transition-colors"
              style="color: var(--text-secondary);"
              aria-label="切換側邊欄">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>

      <h1 class="text-lg font-semibold tracking-tight" style="color: var(--text-primary);">
        CGV Lab Server Assistant
      </h1>

      <slot name="model-name"></slot>
    </div>

    <div class="flex items-center gap-2">
      <!-- Status pill -->
      <div class="flex items-center gap-3 px-3 py-1.5 rounded-full border"
           style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
        <div class="flex items-center gap-1.5">
          <span class="relative flex h-2 w-2">
            <span v-if="isOnline" class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
            <span class="relative inline-flex rounded-full h-2 w-2" :class="isOnline ? 'bg-green-500' : 'bg-red-500'"></span>
          </span>
          <span class="text-[10px] uppercase font-bold tracking-widest" style="color: var(--text-tertiary);">{{ isOnline ? 'Online' : 'Offline' }}</span>
        </div>

        <div class="w-px h-3" style="background-color: var(--border-primary);"></div>

        <div class="text-xs font-mono cursor-default" style="color: var(--text-tertiary);">
          {{ ip }}
        </div>
      </div>

      <!-- Theme toggle -->
      <button
        @click="themeStore.toggleTheme()"
        class="p-2 rounded-lg border transition-all hover:scale-105"
        style="background-color: var(--bg-secondary); border-color: var(--border-primary);"
        title="切換主題"
        aria-label="切換主題"
      >
        <svg v-if="themeStore.isDark" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--accent-warning);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
        </svg>
        <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--accent-primary);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
        </svg>
      </button>

      <slot name="actions"></slot>
    </div>
  </header>
</template>

<style scoped>
.header-sidebar-toggle:is(:hover, :focus-visible) {
  background-color: var(--bg-tertiary);
}
</style>
