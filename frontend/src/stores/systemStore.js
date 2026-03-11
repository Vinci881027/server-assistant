import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi } from '../api/chatApi.js'
import { systemApi } from '../api/systemApi.js'

/**
 * System Store
 *
 * Manages system information, available models, and UI state
 */
export const useSystemStore = defineStore('system', () => {
  // ========== State ==========
  const serverIp = ref('Loading...')
  const availableModels = ref({})
  const statusMessage = ref('')
  const showAdmin = ref(false)

  // ========== Actions ==========

  /**
   * Load system information
   */
  async function loadSystemInfo() {
    try {
      const data = await systemApi.getInfo()
      serverIp.value = data.ip || 'Unknown'
    } catch (error) {
      console.error('Load system info error:', error)
      serverIp.value = 'Error'
    }
  }

  /**
   * Load available AI models
   */
  async function loadModels() {
    try {
      const result = await chatApi.getModels()

      if (result.success) {
        availableModels.value = result.data || {}
      }
    } catch (error) {
      console.error('Load models error:', error)
    }
  }

  /**
   * Set status message
   * @param {string} message - Status message to display
   */
  function setStatusMessage(message) {
    statusMessage.value = message
  }

  /**
   * Clear status message
   */
  function clearStatusMessage() {
    statusMessage.value = ''
  }

  /**
   * Open admin dashboard
   */
  function openAdmin() {
    showAdmin.value = true
  }

  /**
   * Close admin dashboard
   */
  function closeAdmin() {
    showAdmin.value = false
  }

  /**
   * Initialize system data (models and info)
   */
  async function initialize() {
    await Promise.all([
      loadModels(),
      loadSystemInfo(),
    ])
  }

  // ========== Return Public API ==========
  return {
    // State
    serverIp,
    availableModels,
    statusMessage,
    showAdmin,
    // Actions
    loadSystemInfo,
    loadModels,
    setStatusMessage,
    clearStatusMessage,
    openAdmin,
    closeAdmin,
    initialize,
  }
})
