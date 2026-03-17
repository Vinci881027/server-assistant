import { defineStore } from 'pinia'
import { ref } from 'vue'
import { chatApi } from '../api'

/**
 * Conversation Store
 *
 * Manages conversation list and current conversation selection
 */
export const useConversationStore = defineStore('conversation', () => {
  // ========== State ==========
  const conversations = ref([])
  const currentConversationId = ref('')
  const isSidebarOpen = ref(true)
  const isConversationsLoading = ref(true)

  // ========== Actions ==========

  const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

  function normalizeConversations(result) {
    if (Array.isArray(result)) return result
    if (result && result.success && Array.isArray(result.data)) return result.data
    return null
  }

  /**
   * Load all conversations for current user
   */
  async function loadConversations(retry = 1) {
    try {
      const result = await chatApi.getConversations()
      const parsed = normalizeConversations(result)
      if (parsed) {
        conversations.value = parsed
        isConversationsLoading.value = false
        return parsed
      }
      isConversationsLoading.value = false
      return conversations.value
    } catch (error) {
      if (retry > 0) {
        await sleep(250)
        return loadConversations(retry - 1)
      }
      console.error('Load conversations error:', error)
      isConversationsLoading.value = false
      return conversations.value
    }
  }

  /**
   * Select a conversation
   * @param {string} conversationId - Conversation ID to select
   */
  function selectConversation(conversationId) {
    currentConversationId.value = conversationId
  }

  /**
   * Create new conversation
   */
  function createNewConversation() {
    currentConversationId.value = ''
  }

  /**
   * Toggle sidebar visibility
   */
  function toggleSidebar() {
    isSidebarOpen.value = !isSidebarOpen.value
  }

  /**
   * Open sidebar
   */
  function openSidebar() {
    isSidebarOpen.value = true
  }

  /**
   * Close sidebar
   */
  function closeSidebar() {
    isSidebarOpen.value = false
  }

  // ========== Return Public API ==========
  return {
    // State
    conversations,
    currentConversationId,
    isSidebarOpen,
    isConversationsLoading,
    // Actions
    loadConversations,
    selectConversation,
    createNewConversation,
    toggleSidebar,
    openSidebar,
    closeSidebar,
  }
})
