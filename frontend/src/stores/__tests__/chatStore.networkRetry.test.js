import { createPinia, setActivePinia } from 'pinia'
import { describe, it, expect } from 'vitest'
import { chatApi } from '../../api/chatApi.js'
import { useChatStore } from '../chatStore.js'

function makeMessages(start, count) {
  return Array.from({ length: count }, (_, index) => ({
    role: index % 2 === 0 ? 'user' : 'ai',
    content: `message-${start + index}`,
  }))
}

function makeNetworkError() {
  const error = new Error('Network Error - Please check your connection')
  error.code = 'ERR_NETWORK'
  error.request = {}
  return error
}

async function runWithHistoryMock(mockGetHistory, runner) {
  const originalGetHistory = chatApi.getHistory
  chatApi.getHistory = mockGetHistory
  setActivePinia(createPinia())

  try {
    await runner(useChatStore())
  } finally {
    chatApi.getHistory = originalGetHistory
  }
}

describe('chatStore network history retry', () => {
  it('marks loadHistory as pending retry when network request fails', async () => {
    await runWithHistoryMock(
      async () => {
        throw makeNetworkError()
      },
      async (store) => {
        const loaded = await store.loadHistory('conversation-network-fail')

        expect(loaded).toBe(false)
        expect(store.hasPendingHistoryReload).toBe(true)
        expect(store.pendingHistoryReloadConversationId).toBe('conversation-network-fail')
      }
    )
  })

  it('retries pending history load after reconnect and clears pending state on success', async () => {
    let callCount = 0
    await runWithHistoryMock(
      async () => {
        callCount += 1

        if (callCount === 1) {
          throw makeNetworkError()
        }

        return {
          success: true,
          data: {
            messages: makeMessages(0, 4),
            total: 4,
          },
        }
      },
      async (store) => {
        await store.loadHistory('conversation-retry')
        expect(store.hasPendingHistoryReload).toBe(true)

        const retried = await store.retryPendingHistoryReload()
        expect(retried).toBe(true)
        expect(store.messages).toHaveLength(4)
        expect(store.hasPendingHistoryReload).toBe(false)
        expect(store.pendingHistoryReloadConversationId).toBe('')
      }
    )
  })

  it('does not schedule reconnect retry for non-network history errors', async () => {
    await runWithHistoryMock(
      async () => {
        const error = new Error('Server Error')
        error.response = { status: 500 }
        throw error
      },
      async (store) => {
        const loaded = await store.loadHistory('conversation-server-error')

        expect(loaded).toBe(false)
        expect(store.hasPendingHistoryReload).toBe(false)
        expect(store.pendingHistoryReloadConversationId).toBe('')
      }
    )
  })
})
