import { ref, nextTick } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '../stores/authStore'
import { useConversationStore } from '../stores/conversationStore'
import { useSystemStore } from '../stores/systemStore'

export function useKeyboardShortcuts({ sidebarRef, onNewChat } = {}) {
  const authStore = useAuthStore()
  const conversationStore = useConversationStore()
  const systemStore = useSystemStore()

  const { isLoggedIn } = storeToRefs(authStore)
  const { isSidebarOpen } = storeToRefs(conversationStore)
  const { showAdmin } = storeToRefs(systemStore)

  const isShortcutHelpOpen = ref(false)

  async function focusConversationSearch() {
    if (!isLoggedIn.value || showAdmin.value) return

    conversationStore.openSidebar()
    await nextTick()
    await sidebarRef?.value?.focusSearchInput?.()
  }

  function handleGlobalShortcut(event) {
    if (!isLoggedIn.value || showAdmin.value) return
    if (event.defaultPrevented || event.isComposing) return

    if (event.key === 'Escape') {
      if (isShortcutHelpOpen.value) {
        event.preventDefault()
        isShortcutHelpOpen.value = false
        return
      }

      if (isSidebarOpen.value) {
        event.preventDefault()
        conversationStore.closeSidebar()
      }
      return
    }

    const hasPrimaryModifier = event.ctrlKey || event.metaKey
    if (!hasPrimaryModifier || event.altKey) return

    const lowerKey = typeof event.key === 'string' ? event.key.toLowerCase() : ''

    if (lowerKey === 'n' && !event.shiftKey) {
      event.preventDefault()
      onNewChat?.()
      return
    }

    if (lowerKey === 'k' && !event.shiftKey) {
      event.preventDefault()
      void focusConversationSearch()
      return
    }

    if (event.code === 'Slash' || lowerKey === '/' || lowerKey === '?') {
      event.preventDefault()
      isShortcutHelpOpen.value = !isShortcutHelpOpen.value
    }
  }

  return {
    isShortcutHelpOpen,
    handleGlobalShortcut,
  }
}
