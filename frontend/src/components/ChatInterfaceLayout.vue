<script setup>
import { ref, computed } from 'vue'
import Sidebar from './Sidebar.vue'
import ChatHeader from './ChatHeader.vue'
import MessageList from './MessageList.vue'

const sidebarRef = ref(null)
const messageListRef = ref(null)

defineExpose({
  focusSearchInput() {
    return sidebarRef.value?.focusSearchInput?.()
  },
})

const props = defineProps({
  isMobileViewport: { type: Boolean, required: true },
  isSidebarOpen: { type: Boolean, required: true },
  isConversationsLoading: { type: Boolean, default: false },
  conversations: { type: Array, required: true },
  currentConversationId: { type: String, default: '' },
  serverIp: { type: String, default: '' },
  isOnline: { type: Boolean, required: true },
  displayModelName: { type: String, default: '' },
  currentUser: { type: String, default: '' },
  isAdmin: { type: Boolean, default: false },
  messages: { type: Array, required: true },
  isProcessing: { type: Boolean, required: true },
  userInput: { type: String, default: '' },
  model: { type: String, default: '' },
  availableModels: { type: Object, default: () => ({}) },
  statusMessage: { type: String, default: '' },
  isRetrying: { type: Boolean, default: false },
  toolCallStatus: { type: String, default: null },
  retryCountdown: { type: Object, default: () => ({}) },
  onRetry: { type: Function, default: null },
  onCancelRetry: { type: Function, default: null },
  canRegenerateMessage: { type: Function, default: null },
  hasMoreFromServer: { type: Boolean, default: false },
  isHistoryLoading: { type: Boolean, default: false },
  historyLoadFailed: { type: Boolean, default: false },
  isLoadingMore: { type: Boolean, default: false },
})

const pendingCommandMsg = computed(() => {
  if (!props.messages) return null
  for (let i = props.messages.length - 1; i >= 0; i--) {
    const msg = props.messages[i]
    if (msg?.command?.status === 'pending') return msg
  }
  return null
})

const pendingCommandPreview = computed(() => {
  const cmd = pendingCommandMsg.value?.command?.content
  if (!cmd) return ''
  return cmd.length > 40 ? cmd.slice(0, 38) + '…' : cmd
})

function jumpToPendingCommand() {
  messageListRef.value?.scrollToPendingCommand()
}

defineEmits([
  'touchstart',
  'touchmove',
  'touchend',
  'touchcancel',
  'close-sidebar',
  'new-chat',
  'select-chat',
  'delete-chat',
  'export-chat',
  'toggle-sidebar',
  'open-admin',
  'logout',
  'command-action',
  'update:userInput',
  'update:model',
  'send',
  'stop',
  'edit-message',
  'regenerate-message',
  'load-more',
  'retry-history',
])
</script>

<template>
  <div
    class="flex h-full overflow-hidden relative"
    @touchstart.passive="$emit('touchstart', $event)"
    @touchmove.passive="$emit('touchmove', $event)"
    @touchend="$emit('touchend', $event)"
    @touchcancel="$emit('touchcancel', $event)"
  >
    <Transition name="sidebar-backdrop">
      <button
        v-if="isMobileViewport && isSidebarOpen"
        type="button"
        class="mobile-sidebar-backdrop absolute inset-0 z-30"
        aria-label="關閉側邊欄"
        @click="$emit('close-sidebar')"
      ></button>
    </Transition>

    <!-- Sidebar -->
    <Sidebar
      ref="sidebarRef"
      :class="isMobileViewport ? 'mobile-sidebar-panel absolute inset-y-0 left-0 z-40 shadow-2xl' : ''"
      :isOpen="isSidebarOpen"
      :loading="isConversationsLoading"
      :conversations="conversations"
      :currentId="currentConversationId"
      @new-chat="$emit('new-chat')"
      @select-chat="$emit('select-chat', $event)"
      @delete-chat="$emit('delete-chat', $event)"
      @export-chat="$emit('export-chat', $event)"
    />

    <div class="flex-1 flex flex-col h-full relative min-w-0">
      <!-- Chat Header -->
      <ChatHeader
        :ip="serverIp"
        :isOnline="isOnline"
        @toggle-sidebar="$emit('toggle-sidebar')"
      >
        <template #model-name>
          <span class="text-xs ml-3 px-2.5 py-1 rounded-full font-mono border" style="background-color: var(--bg-tertiary); color: var(--text-secondary); border-color: var(--border-primary);">
            {{ displayModelName }}
          </span>
          <span class="text-xs ml-2 px-2.5 py-1 rounded-full font-mono border" style="background-color: color-mix(in srgb, var(--accent-primary) 15%, transparent); color: var(--accent-primary); border-color: color-mix(in srgb, var(--accent-primary) 30%, transparent);">
            {{ currentUser }}
          </span>
          <button
            v-if="isAdmin"
            @click="$emit('open-admin')"
            class="ml-2 text-xs px-2.5 py-1 rounded-full border transition-all hover:scale-105"
            style="background-color: var(--bg-secondary); color: var(--text-secondary); border-color: var(--border-primary);"
            aria-label="開啟管理面板"
          >
            Admin
          </button>
        </template>

        <template #actions>
          <button
            @click="$emit('logout')"
            class="flex items-center gap-2 px-3 py-2 text-xs font-medium rounded-lg border transition-all group"
            style="background-color: var(--bg-secondary); color: var(--text-primary); border-color: var(--border-primary);"
            title="登出系統"
            aria-label="登出系統"
          >
            <span>登出</span>
            <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
          </button>
        </template>
      </ChatHeader>

      <!-- Pending command sticky banner -->
      <Transition name="pending-banner">
        <div v-if="pendingCommandMsg"
             class="pending-cmd-banner"
             role="alert"
             aria-live="polite"
             aria-atomic="true">
          <span class="pending-cmd-banner-icon" aria-hidden="true">⚠️</span>
          <span class="pending-cmd-banner-text">有待確認的高風險指令：</span>
          <code class="pending-cmd-banner-cmd">{{ pendingCommandPreview }}</code>
          <button type="button" class="pending-cmd-banner-jump" @click="jumpToPendingCommand">
            跳轉確認 →
          </button>
        </div>
      </Transition>

      <!-- Message List -->
      <MessageList
        ref="messageListRef"
        class="flex-1 min-h-0"
        :messages="messages"
        :isProcessing="isProcessing"
        :isAdmin="isAdmin"
        :isOnline="isOnline"
        @command-action="(msg, action) => $emit('command-action', { msg, action })"

        :userInput="userInput"
        @update:userInput="$emit('update:userInput', $event)"
        :model="model"
        @update:model="$emit('update:model', $event)"
        :availableModels="availableModels"
        :statusMessage="statusMessage"
        :isRetrying="isRetrying"
        :toolCallStatus="toolCallStatus"
        :retryCountdown="retryCountdown"
        :onRetry="onRetry"
        :onCancelRetry="onCancelRetry"
        :canRegenerateMessage="canRegenerateMessage"
        :hasMoreFromServer="hasMoreFromServer"
        :isHistoryLoading="isHistoryLoading"
        :historyLoadFailed="historyLoadFailed"
        :isLoadingMore="isLoadingMore"
        @send="$emit('send', $event)"
        @stop="$emit('stop')"
        @edit-message="$emit('edit-message', $event)"
        @regenerate-message="$emit('regenerate-message', $event)"
        @load-more="$emit('load-more')"
        @retry-history="$emit('retry-history')"
      />
    </div>
  </div>
</template>

<style scoped>
/* Pending command sticky banner */
.pending-cmd-banner {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 500;
  border-bottom: 1px solid color-mix(in srgb, var(--accent-warning) 40%, transparent);
  background-color: color-mix(in srgb, var(--accent-warning) 12%, var(--bg-primary));
  color: var(--text-primary);
  overflow: hidden;
  flex-shrink: 0;
}
.pending-cmd-banner-icon {
  flex-shrink: 0;
  font-size: 13px;
  line-height: 1;
}
.pending-cmd-banner-text {
  white-space: nowrap;
  flex-shrink: 0;
  color: var(--text-secondary);
}
.pending-cmd-banner-cmd {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: var(--accent-warning);
  background-color: color-mix(in srgb, var(--accent-warning) 14%, transparent);
  padding: 1px 6px;
  border-radius: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  min-width: 0;
  flex: 1;
  max-width: 280px;
}
.pending-cmd-banner-jump {
  flex-shrink: 0;
  margin-left: auto;
  padding: 3px 10px;
  border-radius: 6px;
  border: 1px solid color-mix(in srgb, var(--accent-warning) 50%, transparent);
  background-color: color-mix(in srgb, var(--accent-warning) 18%, transparent);
  color: var(--accent-warning);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: background-color 0.15s;
}
.pending-cmd-banner-jump:hover {
  background-color: color-mix(in srgb, var(--accent-warning) 28%, transparent);
}

/* Banner transition */
.pending-banner-enter-active,
.pending-banner-leave-active {
  transition: max-height 0.2s ease, opacity 0.2s ease;
  overflow: hidden;
  max-height: 48px;
}
.pending-banner-enter-from,
.pending-banner-leave-to {
  max-height: 0;
  opacity: 0;
}

.mobile-sidebar-backdrop {
  border: none;
  background: rgba(8, 13, 20, 0.42);
}

.sidebar-backdrop-enter-active,
.sidebar-backdrop-leave-active {
  transition: opacity 0.2s ease;
}

.sidebar-backdrop-enter-from,
.sidebar-backdrop-leave-to {
  opacity: 0;
}
</style>
