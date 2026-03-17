import { ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useChatStore } from '../stores/chatStore'
import { useConversationStore } from '../stores/conversationStore'
import { useToastQueue } from './useToastQueue'
import { chatApi } from '../api'

export function useConversationDeletion() {
  const chatStore = useChatStore()
  const conversationStore = useConversationStore()
  const { currentConversationId } = storeToRefs(conversationStore)
  const { undo: showUndoToast, dismissAll } = useToastQueue()

  // Track active pending deletes so undo callbacks can find them
  const pendingDeletes = ref(new Map()) // id → { backup, backupIdx, wasActive }

  /**
   * Handle conversation deletion — optimistic removal with 5-second undo window.
   */
  async function handleDeleteChat(id) {
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

    // Store undo data
    pendingDeletes.value.set(id, { backup: conv, backupIdx, wasActive })

    showUndoToast(`已刪除「${conv.title}」`, {
      duration: 5000,
      data: { conversationId: id },
      onAction: () => restoreConversation(id),
      onExpire: () => commitDelete(id),
    })
  }

  function restoreConversation(id) {
    const pending = pendingDeletes.value.get(id)
    if (!pending) return
    pendingDeletes.value.delete(id)

    const { backup, backupIdx, wasActive } = pending

    // Restore to list at original position
    conversationStore.conversations.splice(backupIdx, 0, backup)

    // Re-select if it was the active conversation
    if (wasActive) {
      conversationStore.selectConversation(id)
      chatStore.clearMessages()
      chatStore.loadHistory(id)
    }
  }

  async function commitDelete(id) {
    const pending = pendingDeletes.value.get(id)
    if (!pending) return
    pendingDeletes.value.delete(id)

    try {
      await chatApi.clearHistory(id)
    } catch (e) {
      console.error('刪除失敗', e)
      await conversationStore.loadConversations()
    }
  }

  function handleUndoDelete() {
    // Legacy — no-op; undo is now handled per-toast via onAction callback
  }

  function clearPendingDelete() {
    dismissAll()
    // Commit any remaining pending deletes immediately
    for (const id of pendingDeletes.value.keys()) {
      commitDelete(id)
    }
  }

  // Expose pendingDelete as a computed-like ref for backward compat
  // (UndoDeleteToast in App.vue may still reference it during migration)
  const pendingDelete = ref(null)

  return {
    pendingDelete,
    handleDeleteChat,
    handleUndoDelete,
    clearPendingDelete,
  }
}
