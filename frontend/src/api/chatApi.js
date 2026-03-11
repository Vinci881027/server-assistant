import httpClient, { notifyUnauthorized } from './httpClient.js'
import { DEFAULTS } from '../constants/index.js'

/**
 * Chat API
 *
 * Handles AI chat interactions, conversation history, and model management
 */

export const chatApi = {
  /**
   * Get available AI models
   * @returns {Promise<{success: boolean, data: Object}>}
   */
  async getModels() {
    const response = await httpClient.get('/ai/models')
    return response.data
  },

  /**
   * Stream chat with AI (POST JSON body; avoid URL query for privacy/length safety).
   * @param {Object} params - Chat parameters
   * @param {string} params.message - User message
   * @param {string} params.conversationId - Conversation ID (optional)
   * @param {string} params.model - Model key (e.g., '20b')
   * @param {AbortSignal} signal - Optional abort signal
   * @returns {Promise<Response>} Fetch response stream
   */
  async streamChat(params, signal) {
    const getCookie = (name) => {
      const cookieString = document.cookie || ''
      const cookies = cookieString.split(';')
      for (const cookie of cookies) {
        const [rawKey, ...rest] = cookie.trim().split('=')
        if (rawKey === name) return decodeURIComponent(rest.join('='))
      }
      return null
    }

    const csrfToken = getCookie('XSRF-TOKEN')
    const headers = { 'Content-Type': 'application/json' }
    if (csrfToken) headers['X-XSRF-TOKEN'] = csrfToken

    const response = await fetch('/api/ai/stream', {
      method: 'POST',
      headers,
      credentials: 'include',
      signal,
      body: JSON.stringify({
        message: params?.message ?? '',
        conversationId: params?.conversationId || null,
        model: params?.model || DEFAULTS.MODEL,
      }),
    })

    if (response.status === 401) {
      notifyUnauthorized()
    }

    return response
  },

  /**
   * Get conversation history (paginated).
   * @param {string} conversationId - Conversation ID
   * @param {number|Object} offsetOrOptions - Legacy offset number or options object.
   * Options: {offset, limit, beforeCreatedAt, beforeId}
   * @param {number} limitArg - Legacy limit when using numeric offset
   * @returns {Promise<{success: boolean, data: {messages: Array, total: number, offset: number, limit: number}}>}
   */
  async getHistory(conversationId, offsetOrOptions = 0, limitArg = 50) {
    const params = { conversationId }
    const hasOptionObject = typeof offsetOrOptions === 'object' && offsetOrOptions !== null

    if (!hasOptionObject) {
      params.offset = offsetOrOptions
      params.limit = limitArg
    } else {
      const {
        offset = 0,
        limit = 50,
        beforeCreatedAt = null,
        beforeId = null,
      } = offsetOrOptions

      params.limit = limit
      const hasCursor = typeof beforeCreatedAt === 'string' &&
        beforeCreatedAt.trim().length > 0 &&
        typeof beforeId === 'number' &&
        Number.isFinite(beforeId)

      if (hasCursor) {
        params.beforeCreatedAt = beforeCreatedAt
        params.beforeId = beforeId
      } else {
        params.offset = offset
      }
    }

    const response = await httpClient.get('/ai/history', {
      params
    })
    return response.data
  },

  /**
   * Clear conversation history
   * @param {string} conversationId - Conversation ID
   * @returns {Promise<{success: boolean, message: string}>}
   */
  async clearHistory(conversationId) {
    const response = await httpClient.delete('/ai/history', {
      params: { conversationId }
    })
    return response.data
  },

  /**
   * Create a new conversation and return a server-generated conversation ID.
   * @returns {Promise<{success: boolean, data: string}>}
   */
  async createConversation() {
    const response = await httpClient.post('/ai/conversations/new')
    return response.data
  },

  /**
   * Get all conversations for current user
   * @returns {Promise<{success: boolean, data: Array<{id: string, title: string}>}>}
   */
  async getConversations() {
    const response = await httpClient.get('/ai/conversations')
    return response.data
  },

  /**
   * Delete the last N messages from a conversation (used by regenerate flow).
   * @param {string} conversationId - Conversation ID
   * @param {number} count - Number of messages to delete from end (default 2)
   * @returns {Promise<{success: boolean}>}
   */
  async deleteLastMessages(conversationId, count = 2) {
    const response = await httpClient.delete('/ai/history/last-messages', {
      params: { conversationId, count },
    })
    return response.data
  },
}
