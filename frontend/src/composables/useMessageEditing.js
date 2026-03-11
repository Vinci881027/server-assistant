import { storeToRefs } from 'pinia'
import { useChatStore } from '../stores/chatStore'
import { useConversationStore } from '../stores/conversationStore'
import { useSystemStore } from '../stores/systemStore'
import { chatApi } from '../api'

const DELETE_LAST_MESSAGES_BATCH_SIZE = 20

export function useMessageEditing({ sendMessage } = {}) {
  const chatStore = useChatStore()
  const conversationStore = useConversationStore()
  const systemStore = useSystemStore()

  const { messages, isProcessing } = storeToRefs(chatStore)
  const { currentConversationId } = storeToRefs(conversationStore)

  /**
   * Handle "load more" — fetches the next older page and prepends to messages.
   */
  async function handleLoadMore() {
    if (currentConversationId.value) {
      await chatStore.loadMoreHistory(currentConversationId.value)
    }
  }

  /**
   * Find the closest user message index before a given index.
   */
  function findPreviousUserMessageIndex(fromIndex) {
    const startIndex = Math.min(fromIndex - 1, messages.value.length - 1)
    for (let idx = startIndex; idx >= 0; idx--) {
      if (messages.value[idx]?.role === 'user') return idx
    }
    return -1
  }

  function canRegenerateMessage(messageIndex) {
    if (isProcessing.value) return false

    const msg = messages.value[messageIndex]
    if (!msg || msg.role !== 'ai') return false
    if (msg.command && (msg.command.status === 'pending' || msg.command.status === 'cancelled')) return false
    if (msg.content.includes('已取消指令') || msg.content.includes('已執行指令')) return false

    return findPreviousUserMessageIndex(messageIndex) >= 0
  }

  async function deleteConversationTailFromServer(messagesToDelete) {
    if (messagesToDelete <= 0) return true
    if (!currentConversationId.value) return true

    let remaining = messagesToDelete
    while (remaining > 0) {
      const batchSize = Math.min(remaining, DELETE_LAST_MESSAGES_BATCH_SIZE)
      try {
        await chatApi.deleteLastMessages(currentConversationId.value, batchSize)
        remaining -= batchSize
      } catch (e) {
        console.warn('deleteLastMessages failed (non-fatal):', e)
        systemStore.setStatusMessage('無法同步更新對話紀錄，請重新整理後再試。')
        return false
      }
    }
    return true
  }

  async function trimMessagesFromIndex(startIndex) {
    if (!Number.isInteger(startIndex) || startIndex < 0 || startIndex >= messages.value.length) {
      return false
    }
    const messagesToDelete = messages.value.length - startIndex
    const synced = await deleteConversationTailFromServer(messagesToDelete)
    if (!synced) return false

    messages.value.splice(startIndex, messagesToDelete)
    systemStore.clearStatusMessage()
    return true
  }

  /**
   * Edit a sent user message: trim trailing history and put content back to input box.
   */
  async function handleEditMessage(messageIndex) {
    if (isProcessing.value) return

    const msg = messages.value[messageIndex]
    if (!msg || msg.role !== 'user') return

    const trimmed = await trimMessagesFromIndex(messageIndex)
    if (!trimmed) return

    chatStore.setUserInput(msg.content || '')
  }

  /**
   * Regenerate from a specific AI response by removing it and its source prompt, then resending.
   */
  async function handleRegenerateMessage(messageIndex) {
    if (!canRegenerateMessage(messageIndex)) return

    const userMessageIndex = findPreviousUserMessageIndex(messageIndex)
    if (userMessageIndex < 0) return

    const userMessage = messages.value[userMessageIndex]
    const text = userMessage?.content || ''
    if (!text.trim()) return

    const trimmed = await trimMessagesFromIndex(userMessageIndex)
    if (!trimmed) return

    await sendMessage(text)
  }

  return {
    handleLoadMore,
    canRegenerateMessage,
    handleEditMessage,
    handleRegenerateMessage,
  }
}
