<script setup>
defineProps({
  pendingDelete: {
    type: Object,
    default: null,
  },
})

defineEmits(['undo', 'dismiss'])
</script>

<template>
  <Transition name="toast-slide">
    <div
      v-if="pendingDelete"
      class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 rounded-xl shadow-lg px-4 py-3 flex flex-col gap-2 min-w-[280px] max-w-sm"
      style="background-color: var(--bg-tertiary); border: 1px solid var(--border-primary);"
    >
      <div class="flex items-center justify-between gap-4">
        <span class="text-sm truncate" style="color: var(--text-primary);">
          已刪除「{{ pendingDelete.title }}」
        </span>
        <button
          @click="$emit('undo')"
          class="shrink-0 text-xs font-semibold px-2.5 py-1 rounded-lg transition-all hover:scale-105"
          style="background-color: color-mix(in srgb, var(--accent-primary) 20%, transparent); color: var(--accent-primary);"
        >
          復原
        </button>
      </div>
      <div class="h-0.5 rounded-full overflow-hidden" style="background-color: var(--border-primary);">
        <div class="h-full rounded-full toast-countdown" style="background-color: var(--accent-primary);"></div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.toast-countdown {
  animation: countdown 5s linear forwards;
  transform-origin: left;
}

@keyframes countdown {
  from { width: 100%; }
  to   { width: 0%; }
}

.toast-slide-enter-active,
.toast-slide-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-slide-enter-from,
.toast-slide-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(12px);
}
</style>
