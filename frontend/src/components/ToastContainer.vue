<script setup>
defineProps({
  toasts: {
    type: Array,
    required: true,
  },
})

defineEmits(['action', 'dismiss'])
</script>

<template>
  <TransitionGroup name="toast-stack" tag="div" class="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 flex flex-col-reverse gap-2 items-center pointer-events-none">
    <div
      v-for="toast in toasts"
      :key="toast.id"
      class="pointer-events-auto rounded-xl shadow-lg border"
      :class="toast.type === 'undo' ? 'px-4 py-3 min-w-[280px] max-w-sm' : 'px-4 py-2'"
      style="background-color: var(--bg-tertiary); border-color: var(--border-primary);"
    >
      <!-- Info toast -->
      <template v-if="toast.type === 'info'">
        <span
          class="text-sm font-medium"
          style="color: var(--text-primary);"
        >
          {{ toast.message }}
        </span>
      </template>

      <!-- Undo toast -->
      <template v-else-if="toast.type === 'undo'">
        <div class="flex flex-col gap-2">
          <div class="flex items-center justify-between gap-4">
            <span class="text-sm truncate" style="color: var(--text-primary);">
              {{ toast.message }}
            </span>
            <button
              @click="$emit('action', toast.id)"
              class="shrink-0 text-xs font-semibold px-2.5 py-1 rounded-lg transition-all hover:scale-105"
              style="background-color: color-mix(in srgb, var(--accent-primary) 20%, transparent); color: var(--accent-primary);"
            >
              復原
            </button>
          </div>
          <div class="h-0.5 rounded-full overflow-hidden" style="background-color: var(--border-primary);">
            <div
              class="h-full rounded-full toast-countdown"
              :style="`background-color: var(--accent-primary); animation-duration: ${toast.duration}ms;`"
            ></div>
          </div>
        </div>
      </template>
    </div>
  </TransitionGroup>
</template>

<style scoped>
.toast-countdown {
  animation: countdown linear forwards;
  animation-name: countdown;
  transform-origin: left;
}

@keyframes countdown {
  from { width: 100%; }
  to   { width: 0%; }
}

.toast-stack-enter-active,
.toast-stack-leave-active {
  transition: opacity 0.25s ease, transform 0.25s ease;
}

.toast-stack-enter-from {
  opacity: 0;
  transform: translateY(12px) scale(0.95);
}

.toast-stack-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.95);
}

.toast-stack-move {
  transition: transform 0.25s ease;
}
</style>
