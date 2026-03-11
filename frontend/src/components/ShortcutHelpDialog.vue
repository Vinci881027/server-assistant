<script setup>
defineProps({
  isOpen: {
    type: Boolean,
    required: true,
  },
})

defineEmits(['close'])
</script>

<template>
  <Transition name="shortcut-fade">
    <div
      v-if="isOpen"
      class="fixed inset-0 z-[70] flex items-center justify-center px-4"
      style="background-color: color-mix(in srgb, #000 45%, transparent);"
      @click="$emit('close')"
    >
      <section
        class="w-full max-w-md rounded-2xl border shadow-2xl p-5"
        style="background-color: var(--bg-secondary); border-color: var(--border-primary);"
        role="dialog"
        aria-modal="true"
        aria-label="快捷鍵說明"
        @click.stop
      >
        <div class="flex items-start justify-between gap-3">
          <div>
            <h2 class="text-base font-semibold" style="color: var(--text-primary);">快捷鍵</h2>
            <p class="text-xs mt-1" style="color: var(--text-tertiary);">快速操作聊天工作區</p>
          </div>
          <button
            class="text-xs px-2.5 py-1 rounded-lg border"
            style="border-color: var(--border-primary); color: var(--text-secondary); background-color: var(--bg-tertiary);"
            @click="$emit('close')"
            aria-label="關閉快捷鍵說明"
          >
            關閉
          </button>
        </div>

        <div class="mt-4 space-y-2">
          <div class="flex items-center justify-between rounded-lg px-3 py-2" style="background-color: var(--bg-tertiary);">
            <span class="text-sm" style="color: var(--text-secondary);">新對話</span>
            <kbd class="px-2 py-1 rounded border text-xs font-mono" style="border-color: var(--border-primary); color: var(--text-primary);">Ctrl + N</kbd>
          </div>
          <div class="flex items-center justify-between rounded-lg px-3 py-2" style="background-color: var(--bg-tertiary);">
            <span class="text-sm" style="color: var(--text-secondary);">搜尋對話</span>
            <kbd class="px-2 py-1 rounded border text-xs font-mono" style="border-color: var(--border-primary); color: var(--text-primary);">Ctrl + K</kbd>
          </div>
          <div class="flex items-center justify-between rounded-lg px-3 py-2" style="background-color: var(--bg-tertiary);">
            <span class="text-sm" style="color: var(--text-secondary);">關閉 Sidebar</span>
            <kbd class="px-2 py-1 rounded border text-xs font-mono" style="border-color: var(--border-primary); color: var(--text-primary);">Esc</kbd>
          </div>
          <div class="flex items-center justify-between rounded-lg px-3 py-2" style="background-color: var(--bg-tertiary);">
            <span class="text-sm" style="color: var(--text-secondary);">顯示快捷鍵說明</span>
            <kbd class="px-2 py-1 rounded border text-xs font-mono" style="border-color: var(--border-primary); color: var(--text-primary);">Ctrl + /</kbd>
          </div>
        </div>
      </section>
    </div>
  </Transition>
</template>

<style scoped>
.shortcut-fade-enter-active,
.shortcut-fade-leave-active {
  transition: opacity 0.16s ease;
}

.shortcut-fade-enter-from,
.shortcut-fade-leave-to {
  opacity: 0;
}
</style>
