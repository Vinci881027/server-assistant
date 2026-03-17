import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useConversationDeletion } from '../useConversationDeletion.js'
import { useToastQueue } from '../useToastQueue.js'
import { useChatStore } from '../../stores/chatStore.js'
import { useConversationStore } from '../../stores/conversationStore.js'
import { chatApi } from '../../api/index.js'

describe('useConversationDeletion', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useToastQueue().dismissAll()
    vi.restoreAllMocks()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  it('removes active conversation optimistically and restores it via undo', async () => {
    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const queue = useToastQueue()

    conversationStore.conversations = [
      { id: 'conv-a', title: 'A' },
      { id: 'conv-b', title: 'B' },
    ]
    conversationStore.selectConversation('conv-a')

    const clearMessagesSpy = vi.spyOn(chatStore, 'clearMessages')
    const loadHistorySpy = vi.spyOn(chatStore, 'loadHistory').mockResolvedValue(true)

    const { handleDeleteChat } = useConversationDeletion()
    await handleDeleteChat('conv-a')

    expect(conversationStore.conversations.map(c => c.id)).toEqual(['conv-b'])
    expect(clearMessagesSpy).toHaveBeenCalledTimes(1)
    expect(loadHistorySpy).toHaveBeenCalledWith('conv-b')
    expect(queue.toasts.value).toHaveLength(1)
    expect(queue.toasts.value[0]).toMatchObject({
      type: 'undo',
      message: '已刪除「A」',
      duration: 5000,
    })

    queue.handleAction(queue.toasts.value[0].id)

    expect(conversationStore.conversations.map(c => c.id)).toEqual(['conv-a', 'conv-b'])
    expect(conversationStore.currentConversationId).toBe('conv-a')
    expect(clearMessagesSpy).toHaveBeenCalledTimes(2)
    expect(loadHistorySpy).toHaveBeenCalledWith('conv-a')
  })

  it('commits delete when undo window expires', async () => {
    vi.useFakeTimers()
    try {
      const conversationStore = useConversationStore()
      const clearHistorySpy = vi.spyOn(chatApi, 'clearHistory').mockResolvedValue({ success: true })
      const createNewConversationSpy = vi.spyOn(conversationStore, 'createNewConversation')

      conversationStore.conversations = [{ id: 'conv-a', title: 'A' }]
      conversationStore.selectConversation('conv-a')

      const { handleDeleteChat } = useConversationDeletion()
      await handleDeleteChat('conv-a')

      await vi.advanceTimersByTimeAsync(5000)

      expect(clearHistorySpy).toHaveBeenCalledWith('conv-a')
      expect(createNewConversationSpy).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('reloads conversation list when commit delete API fails', async () => {
    vi.useFakeTimers()
    try {
      const conversationStore = useConversationStore()
      vi.spyOn(chatApi, 'clearHistory').mockRejectedValue(new Error('boom'))
      const loadConversationsSpy = vi
        .spyOn(conversationStore, 'loadConversations')
        .mockResolvedValue([])

      conversationStore.conversations = [
        { id: 'conv-a', title: 'A' },
        { id: 'conv-b', title: 'B' },
      ]
      conversationStore.selectConversation('conv-b')

      const { handleDeleteChat } = useConversationDeletion()
      await handleDeleteChat('conv-a')
      await vi.advanceTimersByTimeAsync(5000)

      expect(console.error).toHaveBeenCalled()
      expect(loadConversationsSpy).toHaveBeenCalledTimes(1)
    } finally {
      vi.useRealTimers()
    }
  })
})
