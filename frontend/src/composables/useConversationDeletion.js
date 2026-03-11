import { ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useChatStore } from '../stores/chatStore'
import { useConversationStore } from '../stores/conversationStore'
import { chatApi } from '../api'

export function useConversationDeletion() {
  const chatStore = useChatStore()
  const conversationStore = useConversationStore()
  const { currentConversationId } = storeToRefs(conversationStore)

  const pendingDelete = ref(null) // { id, title, backup, backupIdx, wasActive, timeoutId }

  /**
   * Handle conversation deletion — optimistic removal with 5-second undo window.
   */
  async function handleDeleteChat(id) {
    // Commit any in-flight pending delete before starting a new one
    if (pendingDelete.value) {
      clearTimeout(pendingDelete.value.timeoutId)
      const prevId = pendingDelete.value.id
      pendingDelete.value = null
      try { await chatApi.clearHistory(prevId) } catch (e) { console.error('刪除失敗', e) }
    }

    const conv = conversationStore.conversations.find(c => c.id === id)
    if (!conv) return

    const backupIdx = conversationStore.conversations.findIndex(c => c.id === id)
    const wasActive = currentConversationId.value === id

    // Optimistic removal from the list
    conversationStore.conversations.splice(backupIdx, 1)

    // Switch away if this was the active conversation
    if (wasActive) {
      chatStore.clearMessages()
      if (conversationStore.conversations.length > 0) {
        const nextId = conversationStore.conversations[0].id
        conversationStore.selectConversation(nextId)
        chatStore.loadHistory(nextId)
      } else {
        conversationStore.createNewConversation()
      }
    }

    const timeoutId = setTimeout(async () => {
      if (pendingDelete.value?.id === id) {
        pendingDelete.value = null
        try {
          await chatApi.clearHistory(id)
        } catch (e) {
          console.error('刪除失敗', e)
          await conversationStore.loadConversations()
        }
      }
    }, 5000)

    pendingDelete.value = { id, title: conv.title, backup: conv, backupIdx, wasActive, timeoutId }
  }

  /**
   * Undo a pending conversation deletion within the 5-second window.
   */
  function handleUndoDelete() {
    if (!pendingDelete.value) return
    const { timeoutId, backup, backupIdx, wasActive, id } = pendingDelete.value
    clearTimeout(timeoutId)
    pendingDelete.value = null

    // Restore to list at original position
    conversationStore.conversations.splice(backupIdx, 0, backup)

    // Re-select if it was the active conversation
    if (wasActive) {
      conversationStore.selectConversation(id)
      chatStore.clearMessages()
      chatStore.loadHistory(id)
    }
  }

  function clearPendingDelete() {
    if (pendingDelete.value) {
      clearTimeout(pendingDelete.value.timeoutId)
      pendingDelete.value = null
    }
  }

  return {
    pendingDelete,
    handleDeleteChat,
    handleUndoDelete,
    clearPendingDelete,
  }
}
