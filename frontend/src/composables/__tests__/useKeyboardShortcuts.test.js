import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useKeyboardShortcuts } from '../useKeyboardShortcuts.js'
import { useAuthStore } from '../../stores/authStore.js'
import { useConversationStore } from '../../stores/conversationStore.js'
import { useSystemStore } from '../../stores/systemStore.js'

async function flushMicrotasks() {
  await Promise.resolve()
  await Promise.resolve()
}

function createEvent(overrides = {}) {
  return {
    key: '',
    code: '',
    ctrlKey: false,
    metaKey: false,
    altKey: false,
    shiftKey: false,
    isComposing: false,
    defaultPrevented: false,
    preventDefault: vi.fn(),
    ...overrides,
  }
}

describe('useKeyboardShortcuts', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('handles Ctrl+N to start a new chat', () => {
    const authStore = useAuthStore()
    const systemStore = useSystemStore()
    authStore.isLoggedIn = true
    systemStore.showAdmin = false

    const onNewChat = vi.fn()
    const { handleGlobalShortcut } = useKeyboardShortcuts({ onNewChat })
    const event = createEvent({ key: 'n', ctrlKey: true })

    handleGlobalShortcut(event)

    expect(event.preventDefault).toHaveBeenCalledTimes(1)
    expect(onNewChat).toHaveBeenCalledTimes(1)
  })

  it('handles Ctrl+K to open sidebar and focus search input', async () => {
    const authStore = useAuthStore()
    const conversationStore = useConversationStore()
    const systemStore = useSystemStore()
    authStore.isLoggedIn = true
    systemStore.showAdmin = false
    conversationStore.isSidebarOpen = false

    const focusSearchInput = vi.fn().mockResolvedValue(undefined)
    const sidebarRef = { value: { focusSearchInput } }
    const { handleGlobalShortcut } = useKeyboardShortcuts({ sidebarRef })
    const event = createEvent({ key: 'k', ctrlKey: true })

    handleGlobalShortcut(event)
    await flushMicrotasks()

    expect(event.preventDefault).toHaveBeenCalledTimes(1)
    expect(conversationStore.isSidebarOpen).toBe(true)
    expect(focusSearchInput).toHaveBeenCalledTimes(1)
  })

  it('toggles shortcut help and closes sidebar with Escape in the right order', () => {
    const authStore = useAuthStore()
    const conversationStore = useConversationStore()
    const systemStore = useSystemStore()
    authStore.isLoggedIn = true
    systemStore.showAdmin = false
    conversationStore.isSidebarOpen = true

    const closeSidebarSpy = vi.spyOn(conversationStore, 'closeSidebar')
    const { handleGlobalShortcut, isShortcutHelpOpen } = useKeyboardShortcuts()

    handleGlobalShortcut(createEvent({ key: '/', code: 'Slash', ctrlKey: true }))
    expect(isShortcutHelpOpen.value).toBe(true)

    handleGlobalShortcut(createEvent({ key: 'Escape' }))
    expect(isShortcutHelpOpen.value).toBe(false)
    expect(closeSidebarSpy).not.toHaveBeenCalled()
    expect(conversationStore.isSidebarOpen).toBe(true)

    handleGlobalShortcut(createEvent({ key: 'Escape' }))
    expect(closeSidebarSpy).toHaveBeenCalledTimes(1)
    expect(conversationStore.isSidebarOpen).toBe(false)
  })

  it('ignores shortcuts when not logged in or admin panel is open', () => {
    const authStore = useAuthStore()
    const systemStore = useSystemStore()
    const onNewChat = vi.fn()
    const { handleGlobalShortcut } = useKeyboardShortcuts({ onNewChat })

    authStore.isLoggedIn = false
    handleGlobalShortcut(createEvent({ key: 'n', ctrlKey: true }))
    expect(onNewChat).not.toHaveBeenCalled()

    authStore.isLoggedIn = true
    systemStore.showAdmin = true
    handleGlobalShortcut(createEvent({ key: 'n', ctrlKey: true }))
    expect(onNewChat).not.toHaveBeenCalled()
  })
})
