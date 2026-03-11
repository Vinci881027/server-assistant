import httpClient from './httpClient'

/**
 * Authentication API
 *
 * Handles user login, logout, and session status
 */

export const authApi = {
  /**
   * Login with username and password
   * @param {string} username - Linux system username
   * @param {string} password - User password
   * @returns {Promise<{success: boolean, data: {user: string, isAdmin: boolean}, message: string}>}
   */
  async login(username, password) {
    const response = await httpClient.post('/login', {
      username,
      password,
    })
    return response.data
  },

  /**
   * Logout current user
   * @returns {Promise<{success: boolean, message: string}>}
   */
  async logout() {
    const response = await httpClient.post('/logout')
    return response.data
  },

  /**
   * Check current login status
   * @returns {Promise<{success: boolean, data: {user: string, isAdmin: boolean}}>}
   */
  async checkStatus() {
    const response = await httpClient.get('/status')
    return response.data
  },
}
