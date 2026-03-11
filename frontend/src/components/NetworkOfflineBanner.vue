<script setup>
defineProps({
  show: {
    type: Boolean,
    required: true,
  },
})
</script>

<template>
  <Transition name="network-banner">
    <div
      v-if="show"
      class="network-offline-banner"
      role="status"
      aria-live="polite"
    >
      網路連線中斷，恢復後會自動重新載入目前對話歷史。
    </div>
  </Transition>
</template>

<style scoped>
.network-offline-banner {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 60;
  padding: 0.65rem 1rem;
  text-align: center;
  font-size: 0.875rem;
  font-weight: 600;
  border-bottom: 1px solid color-mix(in srgb, var(--accent-warning) 45%, var(--border-primary));
  background: color-mix(in srgb, var(--accent-warning) 15%, var(--bg-secondary));
  color: color-mix(in srgb, var(--accent-warning) 68%, var(--text-primary));
  backdrop-filter: blur(8px);
}

.network-banner-enter-active,
.network-banner-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.network-banner-enter-from,
.network-banner-leave-to {
  opacity: 0;
  transform: translateY(-12px);
}
</style>
