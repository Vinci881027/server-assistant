<script setup>
import { onMounted, onBeforeUnmount, watch, defineAsyncComponent, ref } from 'vue'
import { storeToRefs } from 'pinia'
import Login from './components/Login.vue'
import ModelSwitchToast from './components/ModelSwitchToast.vue'
import NetworkOfflineBanner from './components/NetworkOfflineBanner.vue'
import ShortcutHelpDialog from './components/ShortcutHelpDialog.vue'
import UndoDeleteToast from './components/UndoDeleteToast.vue'
import ChatInterfaceLayout from './components/ChatInterfaceLayout.vue'

// Lazy load AdminDashboard (only for admin users)
const AdminDashboard = defineAsyncComponent(() =>
  import('./components/AdminDashboard.vue')
)

// Import stores
import { useAuthStore } from './stores/authStore'
import { useChatStore } from './stores/chatStore'
import { useConversationStore } from './stores/conversationStore'
import { useSystemStore } from './stores/systemStore'

// Import composables
import { useChat } from './composables/useChat'
import { useNetworkStatus } from './composables/useNetworkStatus'
import { useSwipeGesture } from './composables/useSwipeGesture'
import { useKeyboardShortcuts } from './composables/useKeyboardShortcuts'
import { useModelSwitchToast } from './composables/useModelSwitchToast'
import { useCommandConfirmation } from './composables/useCommandConfirmation'
import { useConversationExport } from './composables/useConversationExport'
import { useConversationDeletion } from './composables/useConversationDeletion'
import { useMessageEditing } from './composables/useMessageEditing'
import { resolveModelAutoSwitchPlan, resolveModelLabel } from './utils/modelSwitch'

// ========== Initialize Stores ==========
const authStore = useAuthStore()
const chatStore = useChatStore()
const conversationStore = useConversationStore()
const systemStore = useSystemStore()

// ========== Reactive State from Stores ==========
const { isLoggedIn, currentUser, isAdmin } = storeToRefs(authStore)
const {
  messages,
  userInput,
  isProcessing,
  model,
  displayModelName,
  hasMoreHistory,
  isHistoryLoading,
  isLoadingMore,
  hasPendingHistoryReload,
} = storeToRefs(chatStore)
const { conversations, currentConversationId, isSidebarOpen } = storeToRefs(conversationStore)
const { serverIp, availableModels, showAdmin, statusMessage } = storeToRefs(systemStore)

// ========== Local State ==========
const isInitializing = ref(true)
const chatInterfaceRef = ref(null)
let bootstrapPromise = null

// ========== Initialize Composables ==========
const { sendMessage, stopStreaming, retryNow, isRetrying, retryCountdown } = useChat()

const {
  isBackendOnline,
  isBrowserOnline,
  isOnline,
  handleNetworkReconnected,
  handleNetworkDisconnected,
  startPing,
  stopPing,
} = useNetworkStatus()

const {
  isMobileViewport,
  handleLayoutTouchStart,
  handleLayoutTouchMove,
  handleLayoutTouchEnd,
  setupViewportListener,
  teardownViewportListener,
  onSidebarOpenChange,
} = useSwipeGesture()

const {
  showModelSwitchToast,
  modelSwitchToastMessage,
  suppressNextModelSwitchToast,
  hideModelSwitchToast,
  triggerModelSwitchToast,
} = useModelSwitchToast()

const {
  isShortcutHelpOpen,
  handleGlobalShortcut,
} = useKeyboardShortcuts({
  sidebarRef: chatInterfaceRef,
  onNewChat: handleNewChat,
})

const {
  commandTimeoutHandles,
  clearCommandTimeout,
  clearAllCommandTimeouts,
  schedulePendingCommandTimeout,
  handleCommandAction,
  resetPollTokens,
} = useCommandConfirmation()

const { handleExportChat } = useConversationExport()

const {
  pendingDelete,
  handleDeleteChat,
  handleUndoDelete,
  clearPendingDelete,
} = useConversationDeletion()

const {
  handleLoadMore,
  canRegenerateMessage,
  handleEditMessage,
  handleRegenerateMessage,
} = useMessageEditing({ sendMessage })

// ========== Workspace Lifecycle ==========

function resetWorkspaceState() {
  resetPollTokens()
  clearAllCommandTimeouts()
  hideModelSwitchToast()
  clearPendingDelete()
  systemStore.clearStatusMessage()
  chatStore.clearMessages()
  chatStore.clearAllDrafts()
  conversationStore.conversations = []
  conversationStore.currentConversationId = ''
  isShortcutHelpOpen.value = false
  systemStore.closeAdmin()
}

async function initializeWorkspace() {
  if (!isLoggedIn.value) return
  if (bootstrapPromise) return bootstrapPromise

  bootstrapPromise = (async () => {
    // Prevent welcome-card flash while workspace loads
    isHistoryLoading.value = true

    await Promise.all([
      systemStore.initialize(),
      conversationStore.loadConversations(),
    ])

    if (conversations.value.length === 0) {
      chatStore.clearMessages()
      conversationStore.createNewConversation()
      return
    }

    const hasActive = currentConversationId.value &&
      conversations.value.some(c => c.id === currentConversationId.value)
    const targetId = hasActive ? currentConversationId.value : conversations.value[0].id

    if (targetId) {
      conversationStore.selectConversation(targetId)
      await chatStore.loadHistory(targetId)
    } else {
      isHistoryLoading.value = false
    }
  })().finally(() => {
    bootstrapPromise = null
  })

  return bootstrapPromise
}

// ========== Event Handlers ==========

/**
 * Handle successful login
 */
async function handleLoginSuccess() {
  if (!isLoggedIn.value) {
    const loggedIn = await authStore.checkStatus()
    if (!loggedIn) return
  }
  await initializeWorkspace()
}

/**
 * Handle logout
 */
async function handleLogout() {
  await authStore.logout()
}

/**
 * Handle new chat creation
 */
function handleNewChat() {
  conversationStore.createNewConversation()
  chatStore.clearMessages()
  chatStore.setUserInput('')
  if (isMobileViewport.value) {
    conversationStore.closeSidebar()
  }
}

/**
 * Handle conversation selection
 */
async function handleSelectChat(id) {
  if (currentConversationId.value === id) return
  conversationStore.selectConversation(id)
  if (isMobileViewport.value) {
    conversationStore.closeSidebar()
  }
  await chatStore.loadHistory(id)
}

async function syncWorkspaceConversations() {
  await conversationStore.loadConversations()

  if (conversations.value.length === 0) {
    chatStore.clearMessages()
    conversationStore.createNewConversation()
    return
  }

  const hasActive = currentConversationId.value &&
    conversations.value.some(c => c.id === currentConversationId.value)

  if (!hasActive) {
    const targetId = conversations.value[0].id
    conversationStore.selectConversation(targetId)
    await chatStore.loadHistory(targetId)
  }
}

async function handleAdminConversationsUpdated() {
  await syncWorkspaceConversations()
}

/**
 * Handle admin dashboard close
 */
async function closeAdmin() {
  systemStore.closeAdmin()
  await Promise.all([
    systemStore.loadModels(),
    syncWorkspaceConversations(),
  ])

  // Switch to first available model if current model is disabled
  if (!availableModels.value[model.value] && Object.keys(availableModels.value).length > 0) {
    chatStore.setModel(Object.keys(availableModels.value)[0])
  }
}

// ========== Lifecycle Hooks ==========
onMounted(async () => {
  setupViewportListener()

  window.addEventListener('online', handleNetworkReconnected)
  window.addEventListener('offline', handleNetworkDisconnected)
  window.addEventListener('keydown', handleGlobalShortcut)

  try {
    const loggedIn = await authStore.checkStatus()
    if (loggedIn) {
      await initializeWorkspace()
    }
  } finally {
    isInitializing.value = false
  }
  startPing()
})

watch(isLoggedIn, async (loggedIn, previousLoggedIn) => {
  if (!loggedIn && previousLoggedIn) {
    resetWorkspaceState()
    return
  }

  if (loggedIn && conversations.value.length === 0) {
    await initializeWorkspace()
  }
})

watch(isBackendOnline, async (online, previousOnline) => {
  if (!online || previousOnline) return
  if (!isLoggedIn.value || !hasPendingHistoryReload.value) return
  await chatStore.retryPendingHistoryReload()
})

watch(isSidebarOpen, (isOpen) => {
  onSidebarOpenChange(isOpen)
})

watch(availableModels, (models) => {
  const plan = resolveModelAutoSwitchPlan(model.value, models)
  if (!plan) return

  suppressNextModelSwitchToast.value = true
  chatStore.setModel(plan.targetModelKey)

  const targetLabel = resolveModelLabel(models, plan.targetModelKey)
  if (plan.reason === 'missing') {
    const sourceLabel = plan.currentModelKey
      ? resolveModelLabel(models, plan.currentModelKey)
      : '先前模型'
    triggerModelSwitchToast(`模型「${sourceLabel}」不可用，已自動切換至 ${targetLabel}`)
    return
  }

  const sourceLabel = resolveModelLabel(models, plan.currentModelKey)
  triggerModelSwitchToast(`模型「${sourceLabel}」目前負載高，已自動切換至 ${targetLabel}`)
}, { immediate: true })

watch(currentConversationId, (conversationId) => {
  chatStore.setActiveDraftConversation(conversationId)
}, { immediate: true })

watch(model, (nextModel, previousModel) => {
  if (!nextModel || nextModel === previousModel) return

  if (suppressNextModelSwitchToast.value) {
    suppressNextModelSwitchToast.value = false
    return
  }

  const label = resolveModelLabel(availableModels.value, nextModel)
  triggerModelSwitchToast(`已切換至 ${label}`)
})

watch(
  () => messages.value.map((msg) => ({
    msg,
    status: msg.command?.status,
    inFlight: Boolean(msg.command?.inFlight),
    timeoutAt: msg.command?.timeoutAt,
  })),
  () => {
    const activeMessages = new Set(messages.value)

    for (const msg of messages.value) {
      if (!msg.command || msg.command.status !== 'pending' || msg.command.inFlight) {
        clearCommandTimeout(msg)
        continue
      }
      schedulePendingCommandTimeout(msg)
    }

    for (const trackedMsg of commandTimeoutHandles.keys()) {
      if (!activeMessages.has(trackedMsg)) {
        clearCommandTimeout(trackedMsg)
      }
    }
  },
  { immediate: true }
)

onBeforeUnmount(() => {
  teardownViewportListener()
  window.removeEventListener('online', handleNetworkReconnected)
  window.removeEventListener('offline', handleNetworkDisconnected)
  window.removeEventListener('keydown', handleGlobalShortcut)
  stopPing()
  resetWorkspaceState()
})
</script>

<template>
  <div class="h-screen flex flex-col" style="background-color: var(--bg-primary);">
    <ModelSwitchToast :show="showModelSwitchToast" :message="modelSwitchToastMessage" />

    <NetworkOfflineBanner :show="isLoggedIn && !isBrowserOnline" />

    <ShortcutHelpDialog
      :isOpen="isShortcutHelpOpen && isLoggedIn && !showAdmin"
      @close="isShortcutHelpOpen = false"
    />

    <UndoDeleteToast
      :pendingDelete="pendingDelete"
      @undo="handleUndoDelete"
    />

    <!-- Initial auth check loading state -->
    <div v-if="isInitializing" class="flex-1 flex items-center justify-center" style="background-color: var(--bg-primary);">
      <div class="flex flex-col items-center gap-4">
        <svg class="animate-spin h-8 w-8" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" style="color: var(--accent-primary);">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
        </svg>
        <span class="text-sm" style="color: var(--text-secondary);">載入中…</span>
      </div>
    </div>

    <!-- Login Interface -->
    <Login v-else-if="!isLoggedIn" @login-success="handleLoginSuccess" />

    <!-- Admin Dashboard -->
    <AdminDashboard
      v-else-if="isLoggedIn && showAdmin"
      @close="closeAdmin"
      @conversations-updated="handleAdminConversationsUpdated"
    />

    <!-- Chat Interface -->
    <ChatInterfaceLayout
      v-else-if="isLoggedIn"
      ref="chatInterfaceRef"
      :isMobileViewport="isMobileViewport"
      :isSidebarOpen="isSidebarOpen"
      :conversations="conversations"
      :currentConversationId="currentConversationId"
      :serverIp="serverIp"
      :isOnline="isOnline"
      :displayModelName="displayModelName"
      :currentUser="currentUser"
      :isAdmin="isAdmin"
      :messages="messages"
      :isProcessing="isProcessing"
      :userInput="userInput"
      :model="model"
      :availableModels="availableModels"
      :statusMessage="statusMessage"
      :isRetrying="isRetrying"
      :retryCountdown="retryCountdown"
      :onRetry="retryNow"
      :onCancelRetry="stopStreaming"
      :canRegenerateMessage="canRegenerateMessage"
      :hasMoreFromServer="hasMoreHistory"
      :isHistoryLoading="isHistoryLoading"
      :isLoadingMore="isLoadingMore"
      @touchstart="handleLayoutTouchStart"
      @touchmove="handleLayoutTouchMove"
      @touchend="handleLayoutTouchEnd"
      @touchcancel="handleLayoutTouchEnd"
      @close-sidebar="conversationStore.closeSidebar()"
      @new-chat="handleNewChat"
      @select-chat="handleSelectChat"
      @delete-chat="handleDeleteChat"
      @export-chat="handleExportChat"
      @toggle-sidebar="conversationStore.toggleSidebar()"
      @open-admin="systemStore.openAdmin()"
      @logout="handleLogout"
      @command-action="({ msg, action }) => handleCommandAction(msg, action)"
      @update:userInput="chatStore.setUserInput($event)"
      @update:model="chatStore.setModel($event)"
      @send="sendMessage"
      @stop="stopStreaming"
      @edit-message="handleEditMessage"
      @regenerate-message="handleRegenerateMessage"
      @load-more="handleLoadMore"
    />
  </div>
</template>
