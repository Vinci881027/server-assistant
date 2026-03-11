import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { nextTick } from 'vue'
import { useChatStore } from '../chatStore.js'

const MODEL_PREFERENCE_STORAGE_KEY = 'server-assistant:model-preference'

describe('chatStore model preference', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('hydrates model from localStorage when preference exists', () => {
    localStorage.setItem(MODEL_PREFERENCE_STORAGE_KEY, '120b')

    const store = useChatStore()

    expect(store.model).toBe('120b')
  })

  it('persists model changes to localStorage', async () => {
    const store = useChatStore()

    store.model = '70b'
    await nextTick()

    expect(localStorage.getItem(MODEL_PREFERENCE_STORAGE_KEY)).toBe('70b')
  })
})
