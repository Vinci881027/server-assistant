<script setup>
defineProps({
  show: {
    type: Boolean,
    required: true,
  },
  message: {
    type: String,
    default: '',
  },
})
</script>

<template>
  <Transition name="model-switch-toast">
    <div
      v-if="show"
      class="fixed top-6 left-1/2 -translate-x-1/2 z-50 rounded-xl px-4 py-2 text-sm font-medium shadow-lg border"
      style="background-color: var(--bg-tertiary); color: var(--text-primary); border-color: color-mix(in srgb, var(--accent-primary) 35%, var(--border-primary));"
    >
      {{ message }}
    </div>
  </Transition>
</template>

<style scoped>
.model-switch-toast-enter-active,
.model-switch-toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.model-switch-toast-enter-from,
.model-switch-toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-8px);
}
</style>
