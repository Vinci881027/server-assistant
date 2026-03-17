import { defineStore } from 'pinia'
import { ref } from 'vue'
import { authApi } from '../api'

const UNAUTHORIZED_EVENT = 'app:unauthorized'
let unauthorizedListenerRegistered = false

/**
 * Authentication Store
 *
 * Manages user authentication state and operations
 */
export const useAuthStore = defineStore('auth', () => {
  // ========== State ==========
  const isLoggedIn = ref(false)
  const currentUser = ref('')
  const isAdmin = ref(false)

  function resetAuthState() {
    isLoggedIn.value = false
    currentUser.value = ''
    isAdmin.value = false
  }

  function handleUnauthorized() {
    if (!isLoggedIn.value) return
    resetAuthState()
  }

  function initializeUnauthorizedInterceptor() {
    if (unauthorizedListenerRegistered || typeof window === 'undefined') return
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    unauthorizedListenerRegistered = true
  }

  initializeUnauthorizedInterceptor()

  // ========== Actions ==========

  /**
   * Login with username and password
   * @param {string} username - Linux system username
   * @param {string} password - User password
   * @returns {Promise<{success: boolean, message: string, code?: string, data?: object, transportCode?: string}>}
   */
  async function login(username, password) {
    try {
      const result = await authApi.login(username, password)

      if (result.success) {
        isLoggedIn.value = true
        currentUser.value = result.data.user
        isAdmin.value = result.data.isAdmin
      }

      return result
    } catch (error) {
      console.error('Login error:', error)
      const apiCode = error?.response?.data?.error?.code || null
      const apiData = error?.response?.data?.data || null
      return {
        success: false,
        message: error.message || 'Login failed',
        code: apiCode,
        data: apiData,
        transportCode: error?.code || null,
      }
    }
  }

  /**
   * Logout current user
   * @returns {Promise<{success: boolean}>}
   */
  async function logout() {
    try {
      const result = await authApi.logout()

      if (result.success) {
        resetAuthState()
      }

      return result
    } catch (error) {
      console.error('Logout error:', error)
      resetAuthState()
      return { success: false, message: error.message }
    }
  }

  /**
   * Check current login status
   * @returns {Promise<boolean>} - Whether user is logged in
   */
  async function checkStatus() {
    try {
      const result = await authApi.checkStatus()

      if (result.success) {
        isLoggedIn.value = true
        currentUser.value = result.data.user
        isAdmin.value = result.data.isAdmin
        return true
      } else {
        resetAuthState()
        return false
      }
    } catch (error) {
      console.error('Status check error:', error)
      resetAuthState()
      return false
    }
  }

  // ========== Return Public API ==========
  return {
    // State
    isLoggedIn,
    currentUser,
    isAdmin,
    resetAuthState,
    initializeUnauthorizedInterceptor,
    // Actions
    login,
    logout,
    checkStatus,
  }
})
