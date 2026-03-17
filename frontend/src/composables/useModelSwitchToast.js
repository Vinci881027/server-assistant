import { ref } from 'vue'
import { useToastQueue } from './useToastQueue'

const MODEL_SWITCH_TOAST_DURATION_MS = 2200

export function useModelSwitchToast() {
  const { info } = useToastQueue()
  const suppressNextModelSwitchToast = ref(false)

  function triggerModelSwitchToast(message) {
    if (typeof message !== 'string') return
    const normalizedMessage = message.trim()
    if (!normalizedMessage) return
    info(normalizedMessage, MODEL_SWITCH_TOAST_DURATION_MS)
  }

  function hideModelSwitchToast() {
    // No-op — individual toasts auto-dismiss via the queue.
    // Kept for API compatibility with resetWorkspaceState().
  }

  return {
    // Legacy compat — callers that check showModelSwitchToast still compile,
    // but the value is unused since ToastContainer reads from the queue.
    showModelSwitchToast: ref(false),
    modelSwitchToastMessage: ref(''),
    suppressNextModelSwitchToast,
    hideModelSwitchToast,
    triggerModelSwitchToast,
  }
}
