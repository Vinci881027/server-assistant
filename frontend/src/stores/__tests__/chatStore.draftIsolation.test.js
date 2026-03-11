import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useChatStore } from '../chatStore.js'

describe('chatStore draft isolation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('isolates drafts by conversation id and restores on switch', () => {
    const store = useChatStore()

    store.setActiveDraftConversation('conv-a')
    store.userInput = 'draft A'

    store.setActiveDraftConversation('conv-b')
    expect(store.userInput).toBe('')
    store.userInput = 'draft B'

    store.setActiveDraftConversation('conv-a')
    expect(store.userInput).toBe('draft A')

    store.setActiveDraftConversation('conv-b')
    expect(store.userInput).toBe('draft B')
  })

  it('moves unsaved conversation draft to created conversation id', () => {
    const store = useChatStore()

    store.setActiveDraftConversation('')
    store.userInput = 'draft before create'
    store.moveConversationDraft('', 'conv-created')

    expect(store.getConversationDraft('')).toBe('')
    expect(store.getConversationDraft('conv-created')).toBe('draft before create')
  })
})
