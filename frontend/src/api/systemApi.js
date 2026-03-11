import httpClient from './httpClient.js'

/**
 * System API
 *
 * Handles system information and monitoring
 */

export const systemApi = {
  /**
   * Get system information (IP, hostname, etc.)
   * @returns {Promise<Object>}
   */
  async getInfo() {
    const response = await httpClient.get('/system/info')
    return response.data
  },
}
