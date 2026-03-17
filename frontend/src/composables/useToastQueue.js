import { ref } from 'vue'

const MAX_TOASTS = 3

let nextId = 0

const toasts = ref([])

/**
 * Unified toast queue — max 3 visible, oldest dismissed first.
 *
 * Toast shape:
 *   { id, type: 'info' | 'undo', message, duration, data?, onAction?, onExpire?, timeoutId }
 */
export function useToastQueue() {

  function dismiss(id) {
    const idx = toasts.value.findIndex(t => t.id === id)
    if (idx === -1) return
    const toast = toasts.value[idx]
    if (toast.timeoutId != null) {
      window.clearTimeout(toast.timeoutId)
    }
    toasts.value.splice(idx, 1)
  }

  function addToast({ type = 'info', message, duration = 2200, data = null, onAction = null, onExpire = null }) {
    if (typeof message !== 'string' || !message.trim()) return null

    // Evict oldest while at capacity
    while (toasts.value.length >= MAX_TOASTS) {
      const oldest = toasts.value[0]
      if (oldest.onExpire) oldest.onExpire(oldest)
      dismiss(oldest.id)
    }

    const id = ++nextId
    const timeoutId = window.setTimeout(() => {
      const t = toasts.value.find(t => t.id === id)
      if (t?.onExpire) t.onExpire(t)
      dismiss(id)
    }, duration)

    const toast = { id, type, message: message.trim(), duration, data, onAction, onExpire, timeoutId, createdAt: Date.now() }
    toasts.value.push(toast)
    return id
  }

  /**
   * Convenience: add a short info toast (model switch, status, etc.)
   */
  function info(message, duration = 2200) {
    return addToast({ type: 'info', message, duration })
  }

  /**
   * Convenience: add an undo toast with action callback.
   * Returns the toast id so callers can dismiss it manually.
   */
  function undo(message, { onAction, onExpire, duration = 5000, data = null } = {}) {
    return addToast({ type: 'undo', message, duration, data, onAction, onExpire })
  }

  /**
   * Handle the action button click on an undo toast.
   */
  function handleAction(id) {
    const toast = toasts.value.find(t => t.id === id)
    if (!toast) return
    if (toast.onAction) toast.onAction(toast)
    dismiss(id)
  }

  function dismissAll() {
    while (toasts.value.length > 0) {
      dismiss(toasts.value[0].id)
    }
  }

  return {
    toasts,
    addToast,
    info,
    undo,
    dismiss,
    handleAction,
    dismissAll,
  }
}
