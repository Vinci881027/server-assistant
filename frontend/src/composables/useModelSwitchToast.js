import { ref } from 'vue'

const MODEL_SWITCH_TOAST_DURATION_MS = 2200

export function useModelSwitchToast() {
  const showModelSwitchToast = ref(false)
  const modelSwitchToastMessage = ref('')
  let modelSwitchToastTimeoutId = null
  const suppressNextModelSwitchToast = ref(false)

  function hideModelSwitchToast() {
    if (modelSwitchToastTimeoutId !== null) {
      window.clearTimeout(modelSwitchToastTimeoutId)
      modelSwitchToastTimeoutId = null
    }
    showModelSwitchToast.value = false
    modelSwitchToastMessage.value = ''
  }

  function triggerModelSwitchToast(message) {
    if (typeof message !== 'string') return
    const normalizedMessage = message.trim()
    if (!normalizedMessage) return

    if (modelSwitchToastTimeoutId !== null) {
      window.clearTimeout(modelSwitchToastTimeoutId)
      modelSwitchToastTimeoutId = null
    }

    modelSwitchToastMessage.value = normalizedMessage
    showModelSwitchToast.value = true
    modelSwitchToastTimeoutId = window.setTimeout(() => {
      showModelSwitchToast.value = false
      modelSwitchToastTimeoutId = null
    }, MODEL_SWITCH_TOAST_DURATION_MS)
  }

  return {
    showModelSwitchToast,
    modelSwitchToastMessage,
    suppressNextModelSwitchToast,
    hideModelSwitchToast,
    triggerModelSwitchToast,
  }
}
