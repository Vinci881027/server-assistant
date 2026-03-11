import { ref, computed } from 'vue'
import httpClient from '../api/httpClient'

export function useNetworkStatus() {
  const isBackendOnline = ref(true)
  const isBrowserOnline = ref(getInitialBrowserOnline())
  const isOnline = computed(() => isBrowserOnline.value && isBackendOnline.value)

  let pingInterval = null

  function getInitialBrowserOnline() {
    if (typeof navigator === 'undefined') return true
    return navigator.onLine !== false
  }

  async function checkBackendOnline() {
    if (!isBrowserOnline.value) {
      isBackendOnline.value = false
      return
    }

    try {
      await httpClient.get('/ping', { timeout: 5000 })
      isBackendOnline.value = true
    } catch {
      isBackendOnline.value = false
    }
  }

  async function handleNetworkReconnected() {
    isBrowserOnline.value = true
    await checkBackendOnline()
  }

  function handleNetworkDisconnected() {
    isBrowserOnline.value = false
    isBackendOnline.value = false
  }

  function startPing() {
    void checkBackendOnline()
    pingInterval = setInterval(checkBackendOnline, 30000)
  }

  function stopPing() {
    if (pingInterval !== null) {
      clearInterval(pingInterval)
      pingInterval = null
    }
  }

  return {
    isBackendOnline,
    isBrowserOnline,
    isOnline,
    handleNetworkReconnected,
    handleNetworkDisconnected,
    startPing,
    stopPing,
  }
}
