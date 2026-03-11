import axios from 'axios'

/**
 * Axios HTTP Client Configuration
 *
 * Features:
 * - Automatic CSRF token handling via cookies
 * - Credential inclusion for session management
 * - Unified error handling
 * - Request/Response interceptors
 */

const httpClient = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
  withCredentials: true, // Include cookies for session management
})

const UNAUTHORIZED_EVENT = 'app:unauthorized'
let lastUnauthorizedEventAt = 0

export function notifyUnauthorized() {
  if (typeof window === 'undefined') return

  const now = Date.now()
  if (now - lastUnauthorizedEventAt <= 1000) return

  lastUnauthorizedEventAt = now
  window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
}

// Request interceptor
httpClient.interceptors.request.use(
  (config) => {
    // CSRF token is automatically sent via cookie by the browser
    // Spring Security's CookieCsrfTokenRepository handles this
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// Response interceptor
httpClient.interceptors.response.use(
  (response) => {
    // For ApiResponse<T> format, extract the data
    if (response.data && typeof response.data === 'object') {
      // If it's our ApiResponse format, return it as-is
      if ('success' in response.data) {
        return response
      }
    }
    return response
  },
  (error) => {
    // Handle common errors
    if (error.response) {
      const status = error.response.status
      const data = error.response.data

      // Extract error message from ApiResponse format
      let errorMessage = 'Unknown error'
      if (data && data.message) {
        errorMessage = data.message
      } else if (data && data.error && data.error.message) {
        errorMessage = data.error.message
      }

      switch (status) {
        case 401:
          console.error('Unauthorized - Please login')
          notifyUnauthorized()
          break
        case 403:
          console.error('Forbidden - Insufficient permissions')
          break
        case 404:
          console.error('Not Found:', errorMessage)
          break
        case 500:
          console.error('Server Error:', errorMessage)
          break
        default:
          console.error(`HTTP ${status}:`, errorMessage)
      }

      // Attach parsed error message to error object
      error.message = errorMessage
    } else if (error.request) {
      console.error('Network Error - No response received')
      error.message = 'Network Error - Please check your connection'
    } else {
      console.error('Request Error:', error.message)
    }

    return Promise.reject(error)
  }
)

export default httpClient
