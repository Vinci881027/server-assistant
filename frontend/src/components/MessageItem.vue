<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue';
import ChatCommandRequest from './ChatCommandRequest.vue';
import { COMMAND_CONFIRM_TIMEOUT_SECONDS } from '../config/app.config';

const props = defineProps({
  entry: { type: Object, required: true },
  isProcessing: { type: Boolean, default: false },
  isTouchDevice: { type: Boolean, default: false },
  canRegenerateMessage: { type: Function, default: null },
  collapsedLines: { type: Number, default: 18 },
  isExpanded: { type: Boolean, default: false },
  renderedHtml: { type: String, default: '' },
  shouldCollapse: { type: Boolean, default: false },
  availableModels: { type: Object, default: () => ({}) },
});

const emit = defineEmits([
  'command-action',
  'edit-message',
  'regenerate-message',
  'toggle-expand',
  'touch-start',
  'touch-move',
  'touch-end',
  'copy',
  'switch-model',
]);

const msg = computed(() => props.entry.msg);
const messageKey = computed(() => props.entry.messageKey);
const absoluteIndex = computed(() => props.entry.absoluteIndex);

const shouldShowEditAction = computed(
  () => msg.value?.role === 'user' && !props.isProcessing
);

const modelLabel = computed(() => {
  const key = msg.value?.modelKey
  if (!key || msg.value?.role !== 'ai') return ''
  return props.availableModels[key]?.label || key
})

const shouldShowRegenerateAction = computed(() => {
  if (msg.value?.role !== 'ai' || props.isProcessing) return false;
  if (typeof props.canRegenerateMessage === 'function') {
    return Boolean(props.canRegenerateMessage(absoluteIndex.value));
  }
  return true;
});

// ── Rate-limit countdown ──────────────────────────────────────────────────────
// Shown when the backend told us how long to wait before retrying.
const countdownSec = ref(0)
let countdownTimer = null

const startCountdown = () => {
  const retryableAt = msg.value?.retryableAt
  if (!retryableAt) return
  const update = () => {
    countdownSec.value = Math.max(0, Math.ceil((retryableAt - Date.now()) / 1000))
  }
  update()
  if (countdownSec.value > 0) {
    countdownTimer = setInterval(() => {
      update()
      if (countdownSec.value <= 0) {
        clearInterval(countdownTimer)
        countdownTimer = null
      }
    }, 1000)
  }
}

onMounted(() => {
  if (msg.value?.retryableAt) startCountdown()
})

onBeforeUnmount(() => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
})
// ─────────────────────────────────────────────────────────────────────────────
</script>

<template>
  <div
    :data-msg-key="messageKey"
    :class="['flex gap-3 items-start group', msg.role === 'user' ? 'flex-row-reverse' : 'flex-row']"
  >
    <!-- Avatar -->
    <div
      class="flex-shrink-0 w-9 h-9 rounded-xl flex items-center justify-center overflow-hidden select-none shadow-sm"
      :class="msg.role === 'user' ? 'bg-gradient-to-br from-indigo-500 to-purple-600' : 'bg-gradient-to-br from-emerald-500 to-teal-600'"
    >
      <span v-if="msg.role === 'user'" class="text-xs font-bold text-white">U</span>
      <span v-else class="text-xs font-bold text-white">AI</span>
    </div>

    <!-- Message bubble -->
    <div
      class="max-w-[85%] min-w-0 rounded-2xl p-4 shadow-sm transition-all duration-200"
      @touchstart.passive="emit('touch-start', $event, msg.content)"
      @touchmove.passive="emit('touch-move', $event)"
      @touchend="emit('touch-end')"
      @touchcancel="emit('touch-end')"
      :style="msg.role === 'user'
        ? { backgroundColor: 'var(--user-bubble)', color: 'var(--text-primary)' }
        : { backgroundColor: 'var(--ai-bubble)', border: '1px solid var(--border-primary)' }"
    >
      <div
        class="text-[11px] mb-1.5 flex justify-between items-center gap-4 font-medium tracking-wide"
        style="color: var(--text-tertiary);"
      >
        <span class="flex items-center gap-1.5">
          {{ msg.role === 'user' ? 'User' : 'AI Assistant' }}
          <span
            v-if="modelLabel"
            class="text-[10px] px-1.5 py-0.5 rounded font-mono"
            style="background-color: color-mix(in srgb, var(--accent-primary) 12%, transparent); color: var(--text-tertiary);"
          >{{ modelLabel }}</span>
        </span>
        <div class="message-inline-actions">
          <button
            v-if="shouldShowEditAction"
            type="button"
            class="message-inline-action-btn"
            title="編輯訊息"
            aria-label="編輯訊息"
            @click="emit('edit-message', absoluteIndex)"
          >
            編輯
          </button>
          <button
            v-if="shouldShowRegenerateAction"
            type="button"
            class="message-inline-action-btn"
            title="重新生成回覆"
            aria-label="重新生成回覆"
            @click="emit('regenerate-message', absoluteIndex)"
          >
            重新生成
          </button>
        </div>
      </div>

      <!-- Message content -->
      <div
        class="message-content-shell"
        :class="{ 'message-content-collapsed': shouldCollapse && !isExpanded }"
        :style="{ '--collapsed-lines': String(collapsedLines) }"
      >
        <div
          class="markdown-content leading-7 text-[15px]"
          style="color: var(--text-primary);"
          v-html="renderedHtml"
        ></div>
        <div v-if="shouldCollapse && !isExpanded" class="message-fade-mask"></div>
      </div>
      <div v-if="shouldCollapse" class="mt-1">
        <button
          type="button"
          class="message-expand-btn"
          @click="emit('toggle-expand', messageKey)"
        >
          <svg
            class="message-expand-icon"
            :class="{ 'message-expand-icon-open': isExpanded }"
            viewBox="0 0 16 16"
            fill="currentColor"
          >
            <path d="M4.646 5.646a.5.5 0 0 1 .708 0L8 8.293l2.646-2.647a.5.5 0 0 1 .708.708l-3 3a.5.5 0 0 1-.708 0l-3-3a.5.5 0 0 1 0-.708z" />
          </svg>
          <span>{{ isExpanded ? '收合' : '展開完整輸出' }}</span>
        </button>
      </div>

      <!-- Command confirmation card -->
      <div v-if="msg.command" class="mt-3">
        <ChatCommandRequest
          :command="msg.command.content"
          :status="msg.command.status"
          :disabled="Boolean(msg.command.inFlight) || isProcessing"
          :created-at="msg.command.createdAt"
          :resolved-at="msg.command.resolvedAt"
          :expires-at="msg.command.timeoutAt"
          :ttl-seconds="COMMAND_CONFIRM_TIMEOUT_SECONDS"
          @confirm="emit('command-action', msg, 'confirm')"
          @cancel="emit('command-action', msg, 'cancel')"
          @resend-command="emit('command-action', msg, 'resend')"
        />
      </div>

      <!-- User-aborted indicator -->
      <div v-if="msg.aborted" class="msg-aborted-banner">
        <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
        已中斷回應
      </div>

      <!-- Empty response: retry + switch-model CTAs -->
      <div v-if="msg.emptyResponse && !isProcessing" class="error-action-bar">
        <button
          type="button"
          class="empty-response-retry-btn"
          @click="emit('regenerate-message', absoluteIndex)"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
          重試
        </button>
        <button
          type="button"
          class="error-switch-model-btn"
          @click="emit('switch-model')"
        >
          <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
          切換模型
        </button>
      </div>

      <!-- HTTP / network error CTAs -->
      <div
        v-else-if="msg.errorType && msg.errorType !== 'emptyResponse' && msg.errorType !== 'concurrentStream' && !isProcessing"
        class="error-action-bar"
      >
        <!-- Rate limit: live countdown + retry button enabled after wait -->
        <template v-if="msg.errorType === 'rateLimit'">
          <span v-if="countdownSec > 0" class="error-countdown">
            {{ countdownSec }} 秒後可重試
          </span>
          <button
            type="button"
            class="empty-response-retry-btn"
            :disabled="countdownSec > 0"
            @click="emit('regenerate-message', absoluteIndex)"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
            重試
          </button>
        </template>

        <!-- Auth error: reload to login -->
        <template v-else-if="msg.errorType === 'auth'">
          <button
            type="button"
            class="empty-response-retry-btn"
            @click="() => window.location.reload()"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/></svg>
            重新登入
          </button>
        </template>

        <!-- Network / server errors: retry + switch model -->
        <template v-else>
          <button
            type="button"
            class="empty-response-retry-btn"
            @click="emit('regenerate-message', absoluteIndex)"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
            重試
          </button>
          <button
            v-if="msg.errorType === 'serverUnavailable' || msg.errorType === 'unknown'"
            type="button"
            class="error-switch-model-btn"
            @click="emit('switch-model')"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
            切換模型
          </button>
        </template>
      </div>
    </div>

    <!-- Copy Button -->
    <button
      v-if="!isTouchDevice"
      @click="emit('copy', msg.content, $event)"
      class="mt-2 p-1.5 rounded-lg transition-all opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus:opacity-100"
      style="color: var(--text-tertiary);"
      title="複製內容"
      aria-label="複製訊息內容"
    >
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" />
      </svg>
    </button>
  </div>
</template>
