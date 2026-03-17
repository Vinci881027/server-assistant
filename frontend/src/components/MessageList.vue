<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount, watch } from 'vue';
import { useVirtualizer } from '@tanstack/vue-virtual';
import MessageItem from './MessageItem.vue';
import ControlPanel from './ControlPanel.vue';
import { UI_CONFIG } from '../config/app.config';
import { useMarkdownRenderer } from '../composables/useMarkdownRenderer';
import { appendQuoteToDraft } from '../utils/messageActions';

const props = defineProps({
  messages: { type: Array, required: true },
  isProcessing: { type: Boolean, default: false },
  isAdmin: { type: Boolean, default: false },
  isOnline: { type: Boolean, default: true },
  userInput: { type: String, default: '' },
  model: { type: String, default: '' },
  availableModels: { type: Object, default: () => ({}) },
  statusMessage: { type: String, default: '' },
  isRetrying: { type: Boolean, default: false },
  toolCallStatus: { type: String, default: null },
  retryCountdown: {
    type: Object,
    default: () => ({
      active: false,
      type: '',
      remainingSec: 0,
      totalSec: 0,
    }),
  },
  onRetry: { type: Function, default: null },
  onCancelRetry: { type: Function, default: null },
  canRegenerateMessage: { type: Function, default: null },
  hasMoreFromServer: { type: Boolean, default: false },
  isHistoryLoading: { type: Boolean, default: false },
  historyLoadFailed: { type: Boolean, default: false },
  isLoadingMore: { type: Boolean, default: false },
});

const emit = defineEmits([
  'command-action',
  'update:userInput',
  'update:model',
  'send',
  'stop',
  'edit-message',
  'regenerate-message',
  'load-more',
  'retry-history',
]);

const QUICK_START_COMMANDS = Object.freeze([
  { cmd: '/status', label: '系統狀態', desc: 'CPU、記憶體、磁碟總覽' },
  { cmd: '/docker', label: 'Docker', desc: '快速查看容器與資源使用' },
  { cmd: '/help', label: '指令說明', desc: '列出可用指令與功能' },
]);
const ALL_EXAMPLE_PROMPTS = Object.freeze([
  '我的磁碟快滿了，怎麼辦？',
  '幫我看看有哪些 Docker 容器在跑',
  '系統負載很高，是哪個程式在吃 CPU？',
  '記憶體用量怎麼樣？有沒有快超標？',
  '目前有哪些使用者登入了系統？',
  '有哪些 port 正在監聽中？',
  '幫我查看 /var/log 最新的錯誤訊息',
  '硬碟空間怎麼分布的？哪個目錄最大？',
]);

function pickRandomExamples(pool, count = 3) {
  return [...pool].sort(() => Math.random() - 0.5).slice(0, count);
}

const displayedExamples = ref(pickRandomExamples(ALL_EXAMPLE_PROMPTS));

const refreshExamples = () => {
  displayedExamples.value = pickRandomExamples(ALL_EXAMPLE_PROMPTS);
};
const TOUCH_DEVICE_QUERY = '(hover: none), (pointer: coarse)';
const LONG_PRESS_DELAY_MS = 420;
const LONG_PRESS_MOVE_TOLERANCE_PX = 14;

const isTouchDevice = ref(false);
const messageActionMenu = ref({
  visible: false,
  messageContent: '',
});
let touchDeviceQuery = null;
let longPressTimer = null;
const longPressState = {
  tracking: false,
  startX: 0,
  startY: 0,
};

const MAX_RENDER_LENGTH = Number.isInteger(UI_CONFIG.markdownMaxRenderLength)
  && UI_CONFIG.markdownMaxRenderLength > 0
  ? UI_CONFIG.markdownMaxRenderLength
  : 20000;
const { renderMarkdown } = useMarkdownRenderer(MAX_RENDER_LENGTH);

// Message windowing only affects rendering; the store keeps all loaded history.
const DEFAULT_WINDOW_SIZE = 80;
const DEFAULT_LOAD_CHUNK = 20;
const WINDOW_SIZE = Number.isInteger(UI_CONFIG.messageWindowSize) && UI_CONFIG.messageWindowSize > 0
  ? UI_CONFIG.messageWindowSize
  : DEFAULT_WINDOW_SIZE;
const LOAD_CHUNK = Number.isInteger(UI_CONFIG.messageWindowLoadChunk) && UI_CONFIG.messageWindowLoadChunk > 0
  ? UI_CONFIG.messageWindowLoadChunk
  : DEFAULT_LOAD_CHUNK;
const windowStart = ref(0);

const hasHiddenMessages = computed(() => windowStart.value > 0 || props.hasMoreFromServer);
const visibleMessages = computed(() => props.messages.slice(windowStart.value));
const showHistorySkeleton = computed(() => props.isHistoryLoading && !props.historyLoadFailed);
const showHistoryLoadFailed = computed(() => props.historyLoadFailed && props.messages.length === 0);
const showWelcomeCard = computed(() => !showHistorySkeleton.value && !showHistoryLoadFailed.value && props.messages.length === 0);
const quickStartDisabled = computed(() => props.isProcessing || props.isOnline === false);

// Track pending server load to prevent window from advancing during prepend
let pendingServerLoad = false;
let scrollHeightBeforeLoad = 0;

watch(() => props.isLoadingMore, (loading) => {
  if (loading) {
    pendingServerLoad = true;
    scrollHeightBeforeLoad = messageContainer.value?.scrollHeight ?? 0;
  } else if (pendingServerLoad) {
    pendingServerLoad = false;
    nextTick(() => {
      const el = messageContainer.value;
      if (el && scrollHeightBeforeLoad > 0) {
        el.scrollTop += el.scrollHeight - scrollHeightBeforeLoad;
        scrollHeightBeforeLoad = 0;
      }
    });
  }
});

watch(() => props.messages.length, (newLen, oldLen) => {
  if (newLen <= WINDOW_SIZE) {
    windowStart.value = 0;
    return;
  }
  if (pendingServerLoad) {
    // Messages prepended from server — shift window forward to preserve visual anchor
    const added = newLen - oldLen;
    windowStart.value = Math.min(newLen, windowStart.value + added);
    return;
  }
  // Normal append (AI response) — advance window to keep showing latest
  const naturalStart = Math.max(0, oldLen - WINDOW_SIZE);
  if (windowStart.value >= naturalStart) {
    windowStart.value = Math.max(0, newLen - WINDOW_SIZE);
  }
});

const loadEarlierMessages = () => {
  if (windowStart.value === 0 && props.hasMoreFromServer) {
    // Local window exhausted — fetch older messages from server
    emit('load-more');
    return;
  }
  const el = messageContainer.value;
  const prevScrollHeight = el?.scrollHeight ?? 0;
  windowStart.value = Math.max(0, windowStart.value - LOAD_CHUNK);
  nextTick(() => {
    if (el) el.scrollTop += el.scrollHeight - prevScrollHeight;
  });
};


const messageContainer = ref(null);
const retryBannerRef = ref(null);
const BOTTOM_STICKY_THRESHOLD = 80;
const FLOATING_STACK_GAP = 10;
let resizing = false;

const isNearBottom = (threshold = BOTTOM_STICKY_THRESHOLD) => {
  const el = messageContainer.value;
  if (!el) return true;
  return (el.scrollHeight - el.scrollTop - el.clientHeight) < threshold;
};

const lockScrollToBottom = () => {
  const el = messageContainer.value;
  if (!el) return;
  resizing = true;
  el.scrollTop = el.scrollHeight;
  userAtBottom.value = true;
  requestAnimationFrame(() => {
    resizing = false;
  });
};

const scrollToBottom = () => {
  nextTick(() => {
    lockScrollToBottom();
  });
};

// Track whether user is at the bottom of the message list
const userAtBottom = ref(true);
const autoStickToBottom = ref(true);

const onScroll = () => {
  if (resizing) return; // ignore scroll events caused by resize
  const nearBottom = isNearBottom();
  userAtBottom.value = nearBottom;
  autoStickToBottom.value = nearBottom;
};

let scrollDebounceTimer = null;
watch(() => props.messages.length, () => {
  if (!autoStickToBottom.value) return;
  if (scrollDebounceTimer) return; // already scheduled
  scrollDebounceTimer = requestAnimationFrame(() => {
    scrollDebounceTimer = null;
    if (autoStickToBottom.value) scrollToBottom();
  });
});

// Event delegation handler for code block copy buttons
const handleCodeCopy = async (e) => {
  const btn = e.target.closest('.copy-code-btn');
  if (!btn) return;
  e.preventDefault();
  e.stopPropagation();

  let code = btn.dataset.code
    ?.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"');
  if (!code) {
    // Fallback when sanitizer strips custom data attributes.
    code = btn.closest('.code-block-wrapper')?.querySelector('pre code')?.textContent || '';
  }
  if (!code) return;

  try {
    await writeTextToClipboard(code);
    btn.classList.add('copy-code-btn-copied');
    setTimeout(() => btn.classList.remove('copy-code-btn-copied'), 2000);
  } catch (err) {
    console.error('Failed to copy code:', err);
    btn.classList.add('copy-code-btn-error');
    setTimeout(() => btn.classList.remove('copy-code-btn-error'), 2000);
  }
};

let resizeObserver = null;
onMounted(() => {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    touchDeviceQuery = window.matchMedia(TOUCH_DEVICE_QUERY);
    syncTouchDeviceState(Boolean(touchDeviceQuery.matches));
    if (typeof touchDeviceQuery.addEventListener === 'function') {
      touchDeviceQuery.addEventListener('change', syncTouchDeviceState);
    } else if (typeof touchDeviceQuery.addListener === 'function') {
      touchDeviceQuery.addListener(syncTouchDeviceState);
    }
  }

  scrollToBottom();
  if (messageContainer.value) {
    messageContainer.value.addEventListener('scroll', onScroll, { passive: true });
    messageContainer.value.addEventListener('click', handleCodeCopy);
    resizeObserver = new ResizeObserver(() => {
      if (autoStickToBottom.value && messageContainer.value) {
        lockScrollToBottom();
      }
    });
    resizeObserver.observe(messageContainer.value);
  }
});
onBeforeUnmount(() => {
  if (touchDeviceQuery) {
    if (typeof touchDeviceQuery.removeEventListener === 'function') {
      touchDeviceQuery.removeEventListener('change', syncTouchDeviceState);
    } else if (typeof touchDeviceQuery.removeListener === 'function') {
      touchDeviceQuery.removeListener(syncTouchDeviceState);
    }
  }
  clearThinkingTimer();
  clearLongPressTracking();
  resizeObserver?.disconnect();
  retryBannerObserver?.disconnect();
  messageContainer.value?.removeEventListener('scroll', onScroll);
  messageContainer.value?.removeEventListener('click', handleCodeCopy);
});

const handleComposerResize = () => {
  if (!autoStickToBottom.value) return;
  scrollToBottom();
  requestAnimationFrame(lockScrollToBottom);
};

const handleComposerSend = () => {
  autoStickToBottom.value = true;
  userAtBottom.value = true;
  lockScrollToBottom();
  requestAnimationFrame(lockScrollToBottom);
  emit('send');
};

const runQuickStartCommand = async (command) => {
  if (!command || quickStartDisabled.value) return;
  emit('update:userInput', command);
  await nextTick();
  handleComposerSend();
};

const fillExamplePrompt = (prompt) => {
  if (!prompt || quickStartDisabled.value) return;
  emit('update:userInput', prompt);
};

const jumpToBottom = () => {
  autoStickToBottom.value = true;
  userAtBottom.value = true;
  lockScrollToBottom();
};

// Typing indicator: visible while waiting for the first AI token OR during mid-stream tool calls
const showTypingIndicator = computed(() => {
  if (!props.isProcessing || props.isRetrying) return false;
  const lastMsg = props.messages[props.messages.length - 1];
  if (lastMsg?.role !== 'ai') return false;
  // No content yet (initial thinking / first tool call)
  if (!lastMsg.content) return true;
  // Content exists but a tool is executing mid-stream
  if (props.toolCallStatus) return true;
  return false;
});
const THINKING_STATUS_ESCALATION_SEC = 10;
const thinkingElapsedSec = ref(0);
let thinkingTimer = null;

const clearThinkingTimer = () => {
  if (!thinkingTimer) return;
  clearInterval(thinkingTimer);
  thinkingTimer = null;
};

const startThinkingTimer = () => {
  clearThinkingTimer();
  const startedAt = Date.now();
  thinkingElapsedSec.value = 0;
  thinkingTimer = setInterval(() => {
    thinkingElapsedSec.value = Math.floor((Date.now() - startedAt) / 1000);
  }, 1000);
};

const typingIndicatorText = computed(() => {
  if (props.toolCallStatus) return props.toolCallStatus;
  if (thinkingElapsedSec.value >= THINKING_STATUS_ESCALATION_SEC) return 'AI 仍在思考，請稍候...';
  return 'AI 正在思考中...';
});

const showRetryBanner = computed(() => props.isRetrying && Boolean(props.statusMessage));
const retryProgressPercent = computed(() => {
  const totalSec = Number(props.retryCountdown?.totalSec);
  const remainingSec = Number(props.retryCountdown?.remainingSec);
  if (!props.retryCountdown?.active || !Number.isFinite(totalSec) || totalSec <= 0) return 0;
  if (!Number.isFinite(remainingSec) || remainingSec <= 0) return 0;
  return Math.max(0, Math.min(100, (remainingSec / totalSec) * 100));
});
const retryBannerHeight = ref(0);
const floatingOverlayVars = computed(() => ({
  '--floating-stack-offset': showRetryBanner.value
    ? `${retryBannerHeight.value + FLOATING_STACK_GAP}px`
    : '0px'
}));

const measureRetryBanner = () => {
  retryBannerHeight.value = retryBannerRef.value?.offsetHeight ?? 0;
};

let retryBannerObserver = null;
const bindRetryBannerRef = (el) => {
  if (retryBannerObserver && retryBannerRef.value) {
    retryBannerObserver.unobserve(retryBannerRef.value);
  }
  retryBannerRef.value = el;
  if (!el) {
    retryBannerHeight.value = 0;
    return;
  }
  measureRetryBanner();
  if (typeof ResizeObserver === 'undefined') return;
  if (!retryBannerObserver) {
    retryBannerObserver = new ResizeObserver(() => measureRetryBanner());
  }
  retryBannerObserver.observe(el);
};

watch(showRetryBanner, async (visible) => {
  if (!visible) return;
  await nextTick();
  measureRetryBanner();
});

watch(showTypingIndicator, (visible) => {
  if (visible) {
    startThinkingTimer();
    return;
  }
  clearThinkingTimer();
  thinkingElapsedSec.value = 0;
}, { immediate: true });

const writeTextToClipboard = async (text) => {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Fallback below for environments where clipboard API is unavailable/blocked.
    }
  }
  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.opacity = '0';
  textarea.style.pointerEvents = 'none';
  textarea.style.left = '-9999px';
  document.body.appendChild(textarea);
  textarea.select();
  textarea.setSelectionRange(0, textarea.value.length);
  const copied = document.execCommand('copy');
  document.body.removeChild(textarea);
  if (copied) return true;
  throw new Error('無法存取剪貼簿，請檢查瀏覽器權限設定');
};

const copyToClipboard = async (text, event) => {
  if (!text) return;
  try {
    await writeTextToClipboard(text);
    const btn = event.currentTarget;
    btn.classList.add('msg-copy-btn-copied');
    setTimeout(() => btn.classList.remove('msg-copy-btn-copied'), 2000);
  } catch (err) {
    console.error('Failed to copy:', err);
    const btn = event.currentTarget;
    btn.classList.add('msg-copy-btn-error');
    setTimeout(() => btn.classList.remove('msg-copy-btn-error'), 2000);
  }
};

const syncTouchDeviceState = (matches = null) => {
  if (typeof matches === 'boolean') {
    isTouchDevice.value = matches;
    return;
  }
  isTouchDevice.value = Boolean(touchDeviceQuery?.matches);
};

const clearLongPressTracking = () => {
  if (longPressTimer) {
    clearTimeout(longPressTimer);
    longPressTimer = null;
  }
  longPressState.tracking = false;
  longPressState.startX = 0;
  longPressState.startY = 0;
};

const closeMessageActionMenu = () => {
  messageActionMenu.value = {
    visible: false,
    messageContent: '',
  };
};

const shouldIgnoreLongPressTarget = (target) => {
  return Boolean(target?.closest(
    'button, a, input, textarea, select, [role="button"], [contenteditable="true"], [data-no-long-press]'
  ));
};

const handleMessageTouchStart = (event, messageContent) => {
  clearLongPressTracking();
  if (!isTouchDevice.value) return;
  if (!messageContent?.trim() || messageActionMenu.value.visible) return;
  if (event.touches.length !== 1 || shouldIgnoreLongPressTarget(event.target)) return;

  const touch = event.touches[0];
  longPressState.tracking = true;
  longPressState.startX = touch.clientX;
  longPressState.startY = touch.clientY;
  longPressTimer = setTimeout(() => {
    messageActionMenu.value = {
      visible: true,
      messageContent,
    };
    clearLongPressTracking();
  }, LONG_PRESS_DELAY_MS);
};

const handleMessageTouchMove = (event) => {
  if (!longPressState.tracking || event.touches.length !== 1) return;
  const touch = event.touches[0];
  const deltaX = Math.abs(touch.clientX - longPressState.startX);
  const deltaY = Math.abs(touch.clientY - longPressState.startY);
  if (deltaX > LONG_PRESS_MOVE_TOLERANCE_PX || deltaY > LONG_PRESS_MOVE_TOLERANCE_PX) {
    clearLongPressTracking();
  }
};

const handleMessageTouchEnd = () => {
  clearLongPressTracking();
};

const copyFromMessageActionMenu = async () => {
  const text = messageActionMenu.value.messageContent;
  closeMessageActionMenu();
  if (!text?.trim()) return;
  try {
    await writeTextToClipboard(text);
  } catch (err) {
    console.error('Failed to copy from message action menu:', err);
  }
};

const quoteFromMessageActionMenu = () => {
  const text = messageActionMenu.value.messageContent;
  if (!text?.trim()) {
    closeMessageActionMenu();
    return;
  }
  emit('update:userInput', appendQuoteToDraft(props.userInput, text));
  closeMessageActionMenu();
};

const collapsedLines = 18;
const expandedStates = ref({});
const messageKeyByObject = new WeakMap();
const usedMessageKeys = new Set();
let localMessageKeyCounter = 0;

const lineCountOf = (text) => {
  if (!text) return 0;
  return text.split(/\r?\n/).length;
};

const hashText = (text) => {
  let hash = 2166136261;
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(36);
};

const buildMessageFingerprint = (msg, createdAt = '') => {
  const command = msg?.command;
  return [
    msg?.role ?? '',
    createdAt,
    msg?.content ?? '',
    command?.content ?? '',
    command?.status ?? '',
    command?.timeoutAt ?? '',
    msg?.aborted ? 'aborted' : ''
  ].join('|');
};

const allocateUniqueMessageKey = (baseKey) => {
  let key = baseKey;
  let suffix = 1;
  while (usedMessageKeys.has(key)) {
    suffix += 1;
    key = `${baseKey}#${suffix}`;
  }
  usedMessageKeys.add(key);
  return key;
};

const getMessageStableKey = (msg) => {
  if (!msg || typeof msg !== 'object') {
    return `primitive:${String(msg)}`;
  }

  const existing = messageKeyByObject.get(msg);
  if (existing) return existing;

  const id = msg.id ?? msg.messageId ?? null;
  const createdAt = msg.createdAt ?? msg.command?.createdAt ?? '';
  const createdAtText = createdAt === null || createdAt === undefined ? '' : String(createdAt).trim();
  const fingerprint = buildMessageFingerprint(msg, createdAtText);

  const baseKey = id !== null && id !== undefined && String(id).trim()
    ? `msg:id:${String(id).trim()}`
    : createdAtText
      ? `msg:createdAt:${createdAtText}:${hashText(fingerprint)}`
      : (() => {
          localMessageKeyCounter += 1;
          return `msg:local:${localMessageKeyCounter}:${hashText(fingerprint)}`;
        })();

  const resolvedKey = allocateUniqueMessageKey(baseKey);
  messageKeyByObject.set(msg, resolvedKey);
  return resolvedKey;
};

const visibleMessagesWithKeys = computed(() => visibleMessages.value.map((msg, index) => ({
  msg,
  messageKey: getMessageStableKey(msg),
  absoluteIndex: windowStart.value + index
})));

const messageKeys = computed(() => props.messages.map(getMessageStableKey));

watch(messageKeys, (keys) => {
  const activeKeys = new Set(keys);
  for (const key of Array.from(usedMessageKeys)) {
    if (!activeKeys.has(key)) {
      usedMessageKeys.delete(key);
    }
  }
  if (!Object.keys(expandedStates.value).length) return;
  expandedStates.value = Object.fromEntries(
    Object.entries(expandedStates.value).filter(([key]) => activeKeys.has(key))
  );
});

const shouldCollapseMessage = (msg) => {
  if (!msg || msg.role !== 'ai') return false;
  const content = msg.content || '';
  if (!content.trim()) return false;
  if (msg.command) return false;
  return content.length > 1800 || lineCountOf(content) > 24;
};

const isExpanded = (messageKey) => Boolean(expandedStates.value[messageKey]);

// Virtual scrolling configuration
const VIRTUAL_OVERSCAN = Number.isInteger(UI_CONFIG.virtualScrollOverscan) && UI_CONFIG.virtualScrollOverscan > 0
  ? UI_CONFIG.virtualScrollOverscan
  : 5;
const VIRTUAL_ESTIMATE_SIZE = Number.isInteger(UI_CONFIG.virtualScrollEstimateSize) && UI_CONFIG.virtualScrollEstimateSize > 0
  ? UI_CONFIG.virtualScrollEstimateSize
  : 120;

const virtualItemCount = computed(() => visibleMessagesWithKeys.value.length);

const rowVirtualizer = useVirtualizer(computed(() => ({
  count: virtualItemCount.value,
  getScrollElement: () => messageContainer.value,
  estimateSize: () => VIRTUAL_ESTIMATE_SIZE,
  overscan: VIRTUAL_OVERSCAN,
})));

const virtualItems = computed(() => rowVirtualizer.value.getVirtualItems());
const totalVirtualSize = computed(() => rowVirtualizer.value.getTotalSize());

const measureElement = (el) => {
  if (el) {
    rowVirtualizer.value.measureElement(el);
  }
};

const escapeAttributeSelector = (value) => {
  const text = String(value ?? '');
  if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') {
    return CSS.escape(text);
  }
  return text.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
};

const toggleExpand = (messageKey) => {
  const escapedMessageKey = escapeAttributeSelector(messageKey);
  const shell = messageContainer.value?.querySelector(
    `[data-msg-key="${escapedMessageKey}"] .message-content-shell`
  );
  const expanding = !expandedStates.value[messageKey];

  if (shell) {
    if (expanding) {
      // Collapsed → expanded: animate from clamped height to full scrollHeight
      const from = shell.offsetHeight;
      shell.classList.remove('message-content-collapsed');
      const to = shell.scrollHeight;
      shell.style.maxHeight = from + 'px';
      shell.style.overflow = 'hidden';
      shell.offsetHeight; // reflow
      shell.style.transition = 'max-height 0.35s cubic-bezier(0.4,0,0.2,1)';
      shell.style.maxHeight = to + 'px';
      const onEnd = () => {
        shell.style.transition = '';
        shell.style.maxHeight = '';
        shell.style.overflow = '';
        shell.removeEventListener('transitionend', onEnd);
      };
      shell.addEventListener('transitionend', onEnd, { once: true });
    } else {
      // Expanded → collapsed: animate from current height to clamped height
      const from = shell.scrollHeight;
      const lineH = parseFloat(getComputedStyle(shell).lineHeight) || 1.7 * 16;
      const to = lineH * collapsedLines;
      shell.style.maxHeight = from + 'px';
      shell.style.overflow = 'hidden';
      shell.offsetHeight; // reflow
      shell.style.transition = 'max-height 0.35s cubic-bezier(0.4,0,0.2,1)';
      shell.style.maxHeight = to + 'px';
      const onEnd = () => {
        shell.style.transition = '';
        shell.style.maxHeight = '';
        shell.style.overflow = '';
        shell.classList.add('message-content-collapsed');
        shell.removeEventListener('transitionend', onEnd);
      };
      shell.addEventListener('transitionend', onEnd, { once: true });
    }
  }

  expandedStates.value = {
    ...expandedStates.value,
    [messageKey]: expanding
  };
};

const handleSwitchModel = () => {
  const modelKeys = Object.keys(props.availableModels || {})
  const nextModel = modelKeys.find(k => k !== props.model)
  if (nextModel) {
    emit('update:model', nextModel)
  }
};

defineExpose({
  scrollToPendingCommand() {
    let pendingIdx = -1;
    for (let i = props.messages.length - 1; i >= 0; i--) {
      if (props.messages[i]?.command?.status === 'pending') {
        pendingIdx = i;
        break;
      }
    }
    if (pendingIdx === -1) return;
    if (pendingIdx < windowStart.value) {
      windowStart.value = pendingIdx;
    }
    const visibleIdx = pendingIdx - windowStart.value;
    nextTick(() => {
      rowVirtualizer.value.scrollToIndex(visibleIdx, { behavior: 'smooth' });
    });
  },
});
</script>

<template>
  <div class="flex flex-col h-full relative min-w-0 floating-stack-root" :style="floatingOverlayVars">
    <div ref="messageContainer" class="message-list-container flex-1 overflow-y-auto p-4 md:p-6 custom-scrollbar" role="log" aria-label="對話訊息">
      <div v-if="showHistorySkeleton" class="space-y-4" role="status" aria-live="polite" aria-busy="true">
        <div class="inline-flex items-center gap-2 px-3 py-1 rounded-full text-xs font-medium animate-pulse"
             style="background-color: var(--bg-secondary); color: var(--text-tertiary); border: 1px solid var(--border-primary);">
          <span>載入對話紀錄中...</span>
        </div>
        <div class="flex gap-3 items-start">
          <div class="flex-shrink-0 w-9 h-9 rounded-xl animate-pulse"
               style="background: color-mix(in srgb, var(--accent-primary) 25%, var(--bg-secondary));"></div>
          <div class="flex-1 max-w-[80%] rounded-2xl p-4 border animate-pulse"
               style="background-color: var(--ai-bubble); border-color: var(--border-primary);">
            <div class="h-3.5 w-24 rounded mb-3" style="background-color: color-mix(in srgb, var(--text-tertiary) 20%, transparent);"></div>
            <div class="h-3.5 w-full rounded mb-2" style="background-color: color-mix(in srgb, var(--text-tertiary) 16%, transparent);"></div>
            <div class="h-3.5 w-5/6 rounded" style="background-color: color-mix(in srgb, var(--text-tertiary) 16%, transparent);"></div>
          </div>
        </div>
        <div class="flex gap-3 items-start flex-row-reverse">
          <div class="flex-shrink-0 w-9 h-9 rounded-xl animate-pulse"
               style="background: color-mix(in srgb, var(--accent-primary) 20%, var(--bg-secondary));"></div>
          <div class="flex-1 max-w-[72%] rounded-2xl p-4 border animate-pulse"
               style="background-color: var(--user-bubble); border-color: var(--border-primary);">
            <div class="h-3.5 w-20 rounded mb-3" style="background-color: color-mix(in srgb, var(--text-tertiary) 20%, transparent);"></div>
            <div class="h-3.5 w-full rounded" style="background-color: color-mix(in srgb, var(--text-tertiary) 16%, transparent);"></div>
          </div>
        </div>
      </div>

      <template v-else>
        <div v-if="showHistoryLoadFailed" class="flex flex-col items-center justify-center gap-3 py-16" role="alert">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-10 w-10" fill="none" viewBox="0 0 24 24" stroke="currentColor"
             style="color: var(--text-tertiary);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <p class="text-sm" style="color: var(--text-secondary);">載入對話失敗</p>
        <button
          type="button"
          class="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-medium transition-colors"
          style="background-color: var(--accent-primary); color: var(--bg-primary);"
          @click="emit('retry-history')"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                  d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          重試
        </button>
        </div>

        <section v-if="showWelcomeCard" class="chat-welcome-card" aria-label="首次使用引導">
        <div class="welcome-badge">歡迎使用 Server Assistant</div>
        <h2 class="welcome-title">先試試常用斜線指令</h2>
        <p class="welcome-description">
          系統會直接由後端執行這些查詢，不經 AI 推理，回應更快也更穩定。
        </p>
        <div class="welcome-shortcut-grid">
          <button
            v-for="item in QUICK_START_COMMANDS"
            :key="item.cmd"
            type="button"
            class="welcome-shortcut-btn"
            :disabled="quickStartDisabled"
            @click="runQuickStartCommand(item.cmd)"
          >
            <div class="welcome-shortcut-head">
              <code>{{ item.cmd }}</code>
              <span>{{ item.label }}</span>
            </div>
            <p>{{ item.desc }}</p>
          </button>
        </div>
        <div class="welcome-examples">
          <div class="welcome-examples-header">
            <p class="welcome-examples-label">或試試自然語言提問：</p>
            <button type="button" class="welcome-examples-refresh" @click="refreshExamples" title="換一批範例">↻ 換一批</button>
          </div>
          <div class="welcome-examples-chips">
            <button
              v-for="ex in displayedExamples"
              :key="ex"
              type="button"
              class="welcome-example-chip"
              :disabled="quickStartDisabled"
              @click="fillExamplePrompt(ex)"
            >{{ ex }}</button>
          </div>
        </div>
        <p class="welcome-tip">
          也可以直接輸入自然語言，或使用 <code>!&lt;Linux 指令&gt;</code>（例如 <code>!docker ps</code>）。
        </p>
        </section>

      <!-- Load earlier messages button -->
        <div v-if="hasHiddenMessages" class="flex justify-center">
        <button type="button" @click="loadEarlierMessages"
                :disabled="isLoadingMore"
                class="px-4 py-1.5 text-xs font-medium rounded-full border transition-all hover:scale-105 disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100"
                style="background-color: var(--bg-secondary); color: var(--text-tertiary); border-color: var(--border-primary);">
          <template v-if="isLoadingMore">載入中...</template>
          <template v-else-if="windowStart > 0">載入更早訊息（還有 {{ windowStart }} 則）</template>
          <template v-else>從資料庫載入更多訊息</template>
        </button>
        </div>

        <div v-if="isTouchDevice && visibleMessagesWithKeys.length > 0" class="message-mobile-hint">
        長按訊息可快速「複製」或「引用」
        </div>

      <!-- Virtual scroll container -->
        <div v-if="visibleMessagesWithKeys.length > 0"
             :style="{ height: `${totalVirtualSize}px`, width: '100%', position: 'relative' }">
          <div
            v-for="virtualRow in virtualItems"
            :key="visibleMessagesWithKeys[virtualRow.index].messageKey"
            :ref="measureElement"
            :data-index="virtualRow.index"
            class="virtual-message-row"
            :style="{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${virtualRow.start}px)`,
            }"
          >
            <MessageItem
              :entry="visibleMessagesWithKeys[virtualRow.index]"
              :is-processing="isProcessing"
              :is-touch-device="isTouchDevice"
              :can-regenerate-message="canRegenerateMessage"
              :collapsed-lines="collapsedLines"
              :is-expanded="isExpanded(visibleMessagesWithKeys[virtualRow.index].messageKey)"
              :rendered-html="renderMarkdown(visibleMessagesWithKeys[virtualRow.index].msg.content)"
              :should-collapse="shouldCollapseMessage(visibleMessagesWithKeys[virtualRow.index].msg)"
              :available-models="availableModels"
              @command-action="(msg, action) => emit('command-action', msg, action)"
              @edit-message="(idx) => emit('edit-message', idx)"
              @regenerate-message="(idx) => emit('regenerate-message', idx)"
              @toggle-expand="toggleExpand"
              @switch-model="handleSwitchModel"
              @touch-start="handleMessageTouchStart"
              @touch-move="handleMessageTouchMove"
              @touch-end="handleMessageTouchEnd"
              @copy="copyToClipboard"
            />
          </div>
        </div>

      <!-- Typing Indicator: visible only while waiting for the first AI token -->
        <div v-if="showTypingIndicator" class="flex gap-3 items-start">
          <div class="flex-shrink-0 w-9 h-9 rounded-xl bg-gradient-to-br from-emerald-500 to-teal-600 flex items-center justify-center overflow-hidden select-none shadow-sm">
            <span class="text-xs font-bold text-white">AI</span>
          </div>
          <div class="rounded-2xl p-4 border flex items-center gap-2 shadow-sm"
               style="background-color: var(--ai-bubble); border-color: var(--border-primary);">
            <div class="flex items-center gap-1.5">
              <div class="w-2 h-2 rounded-full animate-bounce" style="background-color: var(--text-tertiary);"></div>
              <div class="w-2 h-2 rounded-full animate-bounce [animation-delay:75ms]" style="background-color: var(--text-tertiary);"></div>
              <div class="w-2 h-2 rounded-full animate-bounce [animation-delay:150ms]" style="background-color: var(--text-tertiary);"></div>
            </div>
            <span class="text-xs" aria-live="polite" style="color: var(--text-tertiary);">{{ typingIndicatorText }}</span>
          </div>
        </div>
      </template>
    </div>

    <Transition name="message-action-menu">
      <div
        v-if="messageActionMenu.visible"
        class="message-action-backdrop"
        @click="closeMessageActionMenu"
      >
        <div
          class="message-action-sheet"
          role="dialog"
          aria-modal="true"
          aria-label="訊息操作選單"
          @click.stop
        >
          <button type="button" class="message-action-btn" @click="copyFromMessageActionMenu">
            複製訊息
          </button>
          <button type="button" class="message-action-btn" @click="quoteFromMessageActionMenu">
            引用到輸入框
          </button>
          <button type="button" class="message-action-btn message-action-btn-cancel" @click="closeMessageActionMenu">
            取消
          </button>
        </div>
      </div>
    </Transition>

    <!-- Retry countdown floating banner -->
    <Transition name="retry-banner">
      <div v-if="showRetryBanner" :ref="bindRetryBannerRef" class="retry-countdown-banner">
        <div class="retry-banner-body">
          <span class="retry-countdown-text">{{ statusMessage }}</span>
          <div class="retry-progress-track" role="progressbar" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.round(retryProgressPercent)">
            <div class="retry-progress-fill" :style="{ width: `${retryProgressPercent}%` }"></div>
          </div>
        </div>
        <div class="retry-banner-actions">
          <button v-if="onRetry" type="button" class="retry-now-btn" @click="onRetry">立即重試</button>
          <button v-if="onCancelRetry" type="button" class="retry-cancel-btn" @click="onCancelRetry">取消</button>
        </div>
      </div>
    </Transition>

    <!-- Jump to bottom button -->
    <Transition name="jump-btn">
      <button v-if="!userAtBottom" type="button" @click="jumpToBottom"
              class="jump-to-bottom-btn"
              title="返回最新訊息" aria-label="返回最新訊息">
        <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 5v14M5 12l7 7 7-7"/>
        </svg>
        <span>返回最新訊息</span>
      </button>
    </Transition>

    <!-- Footer: Input area -->
    <footer class="w-full px-4 md:px-[15%] pb-6 pt-3" style="background-color: var(--bg-primary);">
      <ControlPanel
        :model="model"
        @update:model="val => emit('update:model', val)"
        :availableModels="availableModels"
        :isAdmin="isAdmin"
        :isOnline="isOnline"
        :userInput="userInput"
        @update:userInput="val => emit('update:userInput', val)"
        :isProcessing="isProcessing"
        :statusMessage="isRetrying ? '' : statusMessage"
        @composer-resize="handleComposerResize"
        @send="handleComposerSend"
        @stop="emit('stop')"
      />
      <p class="text-[10px] text-center mt-3" style="color: var(--text-tertiary);">Groq x Spring AI</p>
    </footer>
  </div>
</template>

<style>
.virtual-message-row {
  padding-bottom: 1.5rem; /* equivalent to space-y-6 gap */
}

.message-list-container > :not([style*="position: relative"]) + :not([style*="position: relative"]) {
  margin-top: 1.5rem;
}

.chat-welcome-card {
  border: 1px solid var(--border-primary);
  border-radius: 1rem;
  padding: 1rem;
  background:
    linear-gradient(120deg,
      color-mix(in srgb, var(--accent-primary) 14%, transparent) 0%,
      color-mix(in srgb, var(--bg-secondary) 94%, transparent) 55%,
      color-mix(in srgb, var(--accent-secondary, #06b6d4) 12%, transparent) 100%
    );
  box-shadow: 0 12px 24px color-mix(in srgb, var(--accent-primary) 12%, transparent);
}

.welcome-badge {
  display: inline-flex;
  align-items: center;
  border: 1px solid color-mix(in srgb, var(--accent-primary) 25%, transparent);
  border-radius: 9999px;
  padding: 0.2rem 0.55rem;
  font-size: 0.68rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--accent-primary);
  background-color: color-mix(in srgb, var(--accent-primary) 15%, transparent);
}

.welcome-title {
  margin-top: 0.65rem;
  font-size: 1.15rem;
  font-weight: 700;
  line-height: 1.35;
  color: var(--text-primary);
}

.welcome-description {
  margin-top: 0.45rem;
  color: var(--text-secondary);
  font-size: 0.9rem;
  line-height: 1.5;
}

.welcome-shortcut-grid {
  margin-top: 0.85rem;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0.65rem;
}

.welcome-shortcut-btn {
  text-align: left;
  border-radius: 0.9rem;
  border: 1px solid color-mix(in srgb, var(--border-primary) 80%, transparent);
  padding: 0.75rem;
  background-color: color-mix(in srgb, var(--bg-secondary) 90%, transparent);
  transition: transform 0.18s ease, border-color 0.18s ease, background-color 0.18s ease;
  cursor: pointer;
}

.welcome-shortcut-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--accent-primary) 34%, var(--border-primary));
  background-color: color-mix(in srgb, var(--accent-primary) 12%, var(--bg-secondary));
}

.welcome-shortcut-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.welcome-shortcut-head {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  margin-bottom: 0.35rem;
}

.welcome-shortcut-head code {
  font-size: 0.75rem;
  font-weight: 600;
  padding: 0.12rem 0.4rem;
  border-radius: 0.35rem;
  background-color: color-mix(in srgb, var(--accent-primary) 12%, transparent);
  color: var(--text-primary);
}

.welcome-shortcut-head span {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-primary);
}

.welcome-shortcut-btn p {
  margin: 0;
  color: var(--text-tertiary);
  font-size: 0.78rem;
  line-height: 1.4;
}

.welcome-tip {
  margin-top: 0.8rem;
  color: var(--text-tertiary);
  font-size: 0.76rem;
  line-height: 1.45;
}

.welcome-tip code {
  font-size: 0.74rem;
  padding: 0.08rem 0.28rem;
  border-radius: 0.3rem;
  background-color: color-mix(in srgb, var(--accent-primary) 10%, transparent);
  color: var(--text-secondary);
}

.welcome-examples {
  margin-top: 0.8rem;
}

.welcome-examples-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.45rem;
}

.welcome-examples-label {
  color: var(--text-tertiary);
  font-size: 0.76rem;
  margin-bottom: 0;
}

.welcome-examples-refresh {
  font-size: 0.72rem;
  color: var(--text-tertiary);
  background: none;
  border: none;
  cursor: pointer;
  padding: 0.1rem 0.3rem;
  border-radius: 4px;
  transition: color 0.15s ease, background-color 0.15s ease;
}

.welcome-examples-refresh:hover {
  color: var(--accent-primary);
  background-color: color-mix(in srgb, var(--accent-primary) 10%, transparent);
}

.welcome-examples-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
}

.welcome-example-chip {
  padding: 0.3rem 0.75rem;
  border-radius: 9999px;
  border: 1px solid color-mix(in srgb, var(--accent-primary) 25%, var(--border-primary));
  font-size: 0.78rem;
  color: var(--text-secondary);
  background-color: color-mix(in srgb, var(--bg-secondary) 80%, transparent);
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease;
}

.welcome-example-chip:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--accent-primary) 50%, transparent);
  background-color: color-mix(in srgb, var(--accent-primary) 10%, var(--bg-secondary));
  color: var(--text-primary);
}

.welcome-example-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

@media (max-width: 900px) {
  .welcome-shortcut-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .chat-welcome-card {
    padding: 0.9rem;
  }
  .welcome-title {
    font-size: 1rem;
  }
  .welcome-shortcut-grid {
    grid-template-columns: 1fr;
  }
}

/* Markdown Styles */
.markdown-content {
  overflow-wrap: anywhere;
  word-break: break-word;
}
.markdown-content p,
.markdown-content li,
.markdown-content a,
.markdown-content code {
  overflow-wrap: anywhere;
  word-break: break-word;
}
/* Code block wrapper with header */
.markdown-content .code-block-wrapper {
  position: relative;
  margin: 0.5rem 0;
  border-radius: 0.75rem;
  border: 1px solid var(--border-primary);
  overflow: hidden;
  background-color: var(--code-bg);
}
.markdown-content .code-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.4rem 0.75rem;
  background-color: color-mix(in srgb, var(--code-bg) 90%, var(--text-tertiary) 10%);
  border-bottom: 1px solid var(--border-primary);
  min-height: 32px;
}
.markdown-content .code-block-lang {
  font-size: 0.7rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-tertiary);
  font-family: 'Fira Code', 'JetBrains Mono', monospace;
}
.markdown-content .copy-code-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 0.375rem;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 0.15s, background-color 0.15s;
  margin-left: auto;
}
.markdown-content .copy-code-btn:hover {
  color: var(--text-primary);
  background-color: color-mix(in srgb, var(--text-tertiary) 15%, transparent);
}
/* Copied state: show checkmark via CSS */
.markdown-content .copy-code-btn-copied {
  color: var(--accent-success) !important;
  pointer-events: none;
}
/* Error state: show X icon in red */
.markdown-content .copy-code-btn-error {
  color: var(--accent-danger) !important;
  pointer-events: none;
}
.markdown-content .copy-code-btn svg {
  display: block;
}
.markdown-content .copy-code-btn-copied svg,
.markdown-content .copy-code-btn-error svg {
  display: none;
}
.markdown-content .copy-code-btn::after {
  content: none;
}
.markdown-content .copy-code-btn-copied::after {
  content: '';
  display: block;
  width: 14px;
  height: 14px;
  background: currentColor;
  -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6L9 17l-5-5'/%3E%3C/svg%3E") no-repeat center;
  mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6L9 17l-5-5'/%3E%3C/svg%3E") no-repeat center;
}
.markdown-content .copy-code-btn-error {
  width: auto !important;
  padding: 0 6px !important;
}
.markdown-content .copy-code-btn-error::after {
  content: '複製失敗';
  display: block;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
  line-height: 1;
}

.markdown-content .code-block-wrapper pre {
  margin: 0;
  border: none;
  border-radius: 0;
}
.markdown-content pre {
  background-color: var(--code-bg);
  padding: 1rem;
  border-radius: 0.75rem;
  overflow-x: auto;
  margin: 0.5rem 0;
  border: 1px solid var(--border-primary);
}
.markdown-content code {
  font-family: 'Fira Code', 'JetBrains Mono', monospace;
  background-color: color-mix(in srgb, var(--accent-primary) 10%, transparent);
  color: var(--code-text);
  padding: 0.15em 0.4em;
  border-radius: 0.3em;
  font-size: 0.9em;
}
.markdown-content pre code {
  background-color: transparent;
  padding: 0;
  font-size: 0.85em;
}
.markdown-content p {
  margin-bottom: 0.75rem;
}
.markdown-content p:last-child {
  margin-bottom: 0;
}
.markdown-content ul, .markdown-content ol {
  margin-left: 1.5rem;
  margin-bottom: 0.5rem;
}
.markdown-content li {
  margin-bottom: 0.25rem;
}
.markdown-content a {
  color: var(--accent-primary);
  text-decoration: underline;
  text-decoration-color: color-mix(in srgb, var(--accent-primary) 40%, transparent);
}
.markdown-content a:hover {
  text-decoration-color: var(--accent-primary);
}

.message-content-shell {
  position: relative;
}

.message-content-collapsed {
  max-height: calc(1.7em * var(--collapsed-lines, 18));
  overflow: hidden;
}

.message-fade-mask {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 5rem;
  background: linear-gradient(
    to bottom,
    transparent 0%,
    color-mix(in srgb, var(--ai-bubble) 60%, transparent) 40%,
    color-mix(in srgb, var(--ai-bubble) 95%, transparent) 75%,
    var(--ai-bubble) 100%
  );
  pointer-events: none;
}

.message-expand-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.3rem;
  width: 100%;
  border: none;
  border-top: 1px solid color-mix(in srgb, var(--border-primary) 60%, transparent);
  border-radius: 0;
  padding: 0.45rem 0;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-tertiary);
  background: transparent;
  cursor: pointer;
  transition: color 0.2s ease, background 0.2s ease;
}

.message-expand-btn:hover {
  color: var(--accent-primary);
  background: color-mix(in srgb, var(--accent-primary) 6%, transparent);
}

.message-expand-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}

.message-expand-icon-open {
  transform: rotate(180deg);
}

.floating-stack-root {
  --floating-base-bottom: calc(var(--footer-height, 0px) + 1rem);
  --floating-stack-offset: 0px;
}

/* Retry countdown floating banner */
.retry-countdown-banner {
  position: absolute;
  bottom: var(--floating-base-bottom);
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: flex-end;
  gap: 0.7rem;
  width: min(92vw, 560px);
  padding: 0.55rem 0.75rem;
  border-radius: 14px;
  border: 1px solid color-mix(in srgb, var(--accent-warning, #f59e0b) 35%, var(--border-primary));
  background-color: color-mix(in srgb, var(--accent-warning, #f59e0b) 12%, var(--bg-secondary));
  color: var(--text-primary);
  font-size: 12px;
  font-weight: 500;
  box-shadow: 0 2px 10px rgba(0,0,0,0.18);
  z-index: 11;
  pointer-events: auto;
}
.retry-banner-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.retry-countdown-text {
  color: color-mix(in srgb, var(--accent-warning, #f59e0b) 80%, var(--text-primary));
  line-height: 1.2;
}
.retry-progress-track {
  width: 100%;
  height: 5px;
  border-radius: 9999px;
  background-color: color-mix(in srgb, var(--accent-warning, #f59e0b) 18%, var(--bg-primary));
  overflow: hidden;
}
.retry-progress-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--accent-warning, #f59e0b) 98%, #fde68a) 0%,
    color-mix(in srgb, var(--accent-warning, #f59e0b) 70%, #facc15) 100%);
  transition: width 1s linear;
}
.retry-banner-actions {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-shrink: 0;
}
.retry-now-btn {
  border: none;
  border-radius: 9999px;
  padding: 0.2rem 0.6rem;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  background-color: color-mix(in srgb, var(--accent-warning, #f59e0b) 25%, transparent);
  color: color-mix(in srgb, var(--accent-warning, #f59e0b) 90%, var(--text-primary));
  transition: background-color 0.15s;
}
.retry-now-btn:hover {
  background-color: color-mix(in srgb, var(--accent-warning, #f59e0b) 40%, transparent);
}
.retry-cancel-btn {
  border: 1px solid color-mix(in srgb, var(--border-primary) 75%, transparent);
  border-radius: 9999px;
  padding: 0.2rem 0.6rem;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  background-color: color-mix(in srgb, var(--bg-secondary) 88%, var(--bg-primary));
  color: var(--text-secondary);
  transition: background-color 0.15s, color 0.15s;
}
.retry-cancel-btn:hover {
  background-color: color-mix(in srgb, var(--bg-secondary) 78%, var(--bg-primary));
  color: var(--text-primary);
}
@media (max-width: 560px) {
  .retry-countdown-banner {
    align-items: stretch;
    flex-direction: column;
    gap: 0.5rem;
  }
  .retry-banner-actions {
    justify-content: flex-end;
  }
}
.retry-banner-enter-active,
.retry-banner-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.retry-banner-enter-from,
.retry-banner-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(6px);
}

/* Jump-to-bottom floating button */
.jump-to-bottom-btn {
  position: absolute;
  bottom: calc(var(--floating-base-bottom) + var(--floating-stack-offset));
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 1rem 0.4rem 0.75rem;
  border-radius: 9999px;
  border: 1px solid var(--border-primary);
  background-color: var(--bg-secondary);
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(0,0,0,0.15);
  transition: background-color 0.15s, color 0.15s, box-shadow 0.15s;
  z-index: 10;
  white-space: nowrap;
}
.jump-to-bottom-btn:hover {
  background-color: var(--bg-tertiary, var(--bg-secondary));
  color: var(--accent-primary);
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
}

.jump-btn-enter-active,
.jump-btn-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.jump-btn-enter-from,
.jump-btn-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(6px);
}

.message-action-backdrop {
  position: absolute;
  inset: 0;
  z-index: 24;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 1rem;
  background: rgba(6, 10, 16, 0.4);
}

.message-action-sheet {
  width: min(92vw, 380px);
  border-radius: 1rem;
  border: 1px solid var(--border-primary);
  background-color: var(--bg-secondary);
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.3);
  overflow: hidden;
}

.message-action-btn {
  width: 100%;
  border: none;
  border-bottom: 1px solid color-mix(in srgb, var(--border-primary) 80%, transparent);
  padding: 0.82rem 1rem;
  font-size: 0.9rem;
  font-weight: 600;
  text-align: left;
  color: var(--text-primary);
  background: transparent;
}

.message-action-btn:last-child {
  border-bottom: none;
}

.message-action-btn:active {
  background-color: color-mix(in srgb, var(--accent-primary) 12%, transparent);
}

.message-action-btn-cancel {
  color: var(--text-secondary);
}

.message-action-menu-enter-active,
.message-action-menu-leave-active {
  transition: opacity 0.16s ease;
}

.message-action-menu-enter-from,
.message-action-menu-leave-to {
  opacity: 0;
}

.message-mobile-hint {
  margin-top: 0.25rem;
  margin-bottom: 0.15rem;
  text-align: center;
  font-size: 0.72rem;
  color: var(--text-tertiary);
}

/* User-aborted banner */
.msg-aborted-banner {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  margin-top: 0.6rem;
  padding: 0.2rem 0.6rem;
  border-radius: 9999px;
  font-size: 11px;
  font-weight: 500;
  color: var(--text-tertiary);
  background-color: color-mix(in srgb, var(--text-tertiary) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--text-tertiary) 20%, transparent);
  user-select: none;
}

/* Empty response retry button */
.empty-response-retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  margin-top: 0.6rem;
  padding: 0.35rem 0.85rem;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
  color: var(--accent);
  background-color: color-mix(in srgb, var(--accent) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent) 25%, transparent);
  cursor: pointer;
  transition: background-color 0.15s, border-color 0.15s;
  user-select: none;
}

.empty-response-retry-btn:hover {
  background-color: color-mix(in srgb, var(--accent) 18%, transparent);
  border-color: color-mix(in srgb, var(--accent) 40%, transparent);
}

.empty-response-retry-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  pointer-events: none;
}

/* Error action bar: wraps CTA buttons for error states */
.error-action-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 0.6rem;
}

.error-action-bar .empty-response-retry-btn {
  margin-top: 0;
}

/* Switch-model secondary button */
.error-switch-model-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.35rem 0.85rem;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  background-color: color-mix(in srgb, var(--text-tertiary) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--border-primary) 70%, transparent);
  cursor: pointer;
  transition: background-color 0.15s, border-color 0.15s;
  user-select: none;
}

.error-switch-model-btn:hover {
  background-color: color-mix(in srgb, var(--text-tertiary) 15%, transparent);
  border-color: var(--border-primary);
}

/* Rate-limit countdown label */
.error-countdown {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-tertiary);
  user-select: none;
}

.message-inline-actions {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}

.message-inline-action-btn {
  border: 1px solid color-mix(in srgb, var(--border-primary) 75%, transparent);
  border-radius: 9999px;
  padding: 0.12rem 0.55rem;
  font-size: 10px;
  font-weight: 600;
  line-height: 1.45;
  color: var(--text-tertiary);
  background-color: color-mix(in srgb, var(--bg-secondary) 88%, transparent);
  transition: color 0.15s ease, border-color 0.15s ease, background-color 0.15s ease;
}

.message-inline-action-btn:hover {
  color: var(--accent-primary);
  border-color: color-mix(in srgb, var(--accent-primary) 40%, transparent);
  background-color: color-mix(in srgb, var(--accent-primary) 14%, transparent);
}

.message-inline-action-btn:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent-primary) 55%, transparent);
  outline-offset: 1px;
}

/* Message copy button: hide SVG and show checkmark when copied */
.msg-copy-btn-copied svg,
.msg-copy-btn-error svg {
  display: none;
}
.msg-copy-btn-copied::after {
  content: '';
  display: block;
  width: 16px;
  height: 16px;
  background: var(--accent-success);
  -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6L9 17l-5-5'/%3E%3C/svg%3E") no-repeat center;
  mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6L9 17l-5-5'/%3E%3C/svg%3E") no-repeat center;
}
.msg-copy-btn-error {
  color: var(--accent-danger) !important;
}
.msg-copy-btn-error::after {
  content: '複製失敗';
  display: block;
  font-size: 10px;
  font-weight: 600;
  white-space: nowrap;
  color: var(--accent-danger);
  line-height: 1;
}
</style>
