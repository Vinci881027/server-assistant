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

describe('chatStore pagination', () => {
  it('loadHistory keeps full page and hasMore follows backend total', async () => {
    const calls = []
    await runWithHistoryMock(
      async (_conversationId, options) => {
        calls.push(options)
        return {
          success: true,
          data: {
            messages: makeMessages(0, 50),
            total: 120,
            nextCursorCreatedAt: '2026-03-10T12:00:00',
            nextCursorId: 500,
          },
        }
      },
      async (store) => {
        await store.loadHistory('conversation-1')

        expect(calls).toHaveLength(1)
        expect(calls[0]).toEqual({ limit: 50 })
        expect(store.messages).toHaveLength(50)
        expect(store.hasMoreHistory).toBe(true)
      }
    )
  })

  it('loadMoreHistory uses cursor from previous page', async () => {
    const calls = []
    await runWithHistoryMock(
      async (_conversationId, options) => {
        calls.push(options)

        if (!options.beforeCreatedAt) {
          return {
            success: true,
            data: {
              messages: makeMessages(50, 50),
              total: 120,
              nextCursorCreatedAt: '2026-03-10T11:00:00',
              nextCursorId: 500,
            },
          }
        }

        if (options.beforeCreatedAt === '2026-03-10T11:00:00' && options.beforeId === 500) {
          return {
            success: true,
            data: {
              messages: makeMessages(0, 50),
              total: 120,
              nextCursorCreatedAt: '2026-03-10T10:00:00',
              nextCursorId: 450,
            },
          }
        }

        throw new Error(`unexpected cursor ${JSON.stringify(options)}`)
      },
      async (store) => {
        await store.loadHistory('conversation-2')

        // Simulate a local-only message not fetched from paged history.
        store.messages.push({ role: 'user', content: 'local-only' })

        await store.loadMoreHistory('conversation-2')

        expect(calls).toHaveLength(2)
        expect(calls[1]).toEqual({
          limit: 50,
          beforeCreatedAt: '2026-03-10T11:00:00',
          beforeId: 500,
        })
        expect(store.messages).toHaveLength(101)
        expect(store.hasMoreHistory).toBe(true)
      }
    )
  })

  it('hasMoreHistory turns false only after loaded history reaches total', async () => {
    const calls = []
    await runWithHistoryMock(
      async (_conversationId, options) => {
        calls.push(options)

        if (!options.beforeCreatedAt) {
          return {
            success: true,
            data: {
              messages: makeMessages(70, 50),
              total: 120,
              nextCursorCreatedAt: '2026-03-10T11:00:00',
              nextCursorId: 500,
            },
          }
        }

        if (options.beforeCreatedAt === '2026-03-10T11:00:00' && options.beforeId === 500) {
          return {
            success: true,
            data: {
              messages: makeMessages(20, 50),
              total: 120,
              nextCursorCreatedAt: '2026-03-10T10:00:00',
              nextCursorId: 450,
            },
          }
        }

        if (options.beforeCreatedAt === '2026-03-10T10:00:00' && options.beforeId === 450) {
          return {
            success: true,
            data: {
              messages: makeMessages(0, 20),
              total: 120,
            },
          }
        }

        throw new Error(`unexpected cursor ${JSON.stringify(options)}`)
      },
      async (store) => {
        await store.loadHistory('conversation-3')
        store.messages.push({ role: 'user', content: 'local-only' })

        await store.loadMoreHistory('conversation-3')
        expect(store.hasMoreHistory).toBe(true)

        await store.loadMoreHistory('conversation-3')
        expect(calls).toEqual([
          { limit: 50 },
          { limit: 50, beforeCreatedAt: '2026-03-10T11:00:00', beforeId: 500 },
          { limit: 50, beforeCreatedAt: '2026-03-10T10:00:00', beforeId: 450 },
        ])
        expect(store.hasMoreHistory).toBe(false)
        expect(store.messages).toHaveLength(121)
      }
    )
  })

  it('loadHistory toggles isHistoryLoading during fetch and after completion', async () => {
    let resolveHistory

    await runWithHistoryMock(
      async () => new Promise((resolve) => {
        resolveHistory = resolve
      }),
      async (store) => {
        const pending = store.loadHistory('conversation-4')

        expect(store.isHistoryLoading).toBe(true)

        resolveHistory({
          success: true,
          data: {
            messages: makeMessages(0, 2),
            total: 2,
          },
        })

        await pending
        expect(store.isHistoryLoading).toBe(false)
      }
    )
  })
})
