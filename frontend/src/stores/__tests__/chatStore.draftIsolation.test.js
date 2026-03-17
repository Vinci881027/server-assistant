import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useChatStore } from '../chatStore.js'

describe('chatStore draft isolation', () => {
  beforeEach(() => {
    localStorage.clear()
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
    expect(localStorage.getItem('draft___new_conversation__')).toBeNull()
    expect(localStorage.getItem('draft_conv-created')).toBe('draft before create')
  })

  it('hydrates draft from localStorage when switching conversation', () => {
    localStorage.setItem('draft_conv-from-storage', 'saved draft')
    const store = useChatStore()

    store.setActiveDraftConversation('conv-from-storage')

    expect(store.userInput).toBe('saved draft')
  })

  it('removes persisted draft when clearing an untouched conversation draft', () => {
    localStorage.setItem('draft_conv-clear', 'to clear')
    const store = useChatStore()

    store.clearConversationDraft('conv-clear')

    expect(localStorage.getItem('draft_conv-clear')).toBeNull()
    expect(store.getConversationDraft('conv-clear')).toBe('')
  })
})
