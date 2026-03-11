<script setup>
import { ref, watch, nextTick, computed, onMounted, onBeforeUnmount } from 'vue';
import { CHAT_CONFIG } from '../config/app.config';

const props = defineProps(['isProcessing', 'userInput', 'statusMessage', 'availableModels', 'isAdmin', 'isOnline']);
const emit = defineEmits(['update:model', 'update:userInput', 'send', 'stop', 'composer-resize']);
const model = defineModel('model');
const maxMessageLength = CHAT_CONFIG.maxMessageLength || 8000;

const SLASH_COMMANDS = [
  { cmd: '/addSSHKey', desc: '為既有使用者加入 SSH key', hint: '/addSSHKey [username]' },
  { cmd: '/addUser', desc: '新增使用者', hint: '/addUser [username]' },
  { cmd: '/docker', desc: 'Docker 狀態', hint: '/docker' },
  { cmd: '/gpu', desc: '顯示 GPU 狀態' },
  { cmd: '/help', desc: '列出可用指令與網站功能' },
  { cmd: '/mount', desc: '格式化並掛載整顆磁碟 (Admin)', hint: '/mount <device> <target> [fstype] [options]' },
  { cmd: '/offload', desc: '搬移 home 資料夾到其他硬碟並建立 symlink', hint: '/offload' },
  { cmd: '/port', desc: '列出監聽中的 Port' },
  { cmd: '/status', desc: '顯示系統狀態' },
  { cmd: '/top', desc: '顯示高耗能進程', hint: '/top cpu|mem [limit]' },
  { cmd: '/users', desc: '列出系統可登入使用者' },
];

const ADMIN_ONLY_SLASH_COMMANDS = new Set([
  '/addSSHKey',
  '/addUser',
  '/mount',
  '/offload',
  '/users',
]);

const SLASH_COMMAND_ALIASES = {
  '/add_user': '/addUser',
  '/createuser': '/addUser',
  '/create_user': '/addUser',
  '/add_ssh_key': '/addSSHKey',
  '/addssh': '/addSSHKey',
  '/sshkey': '/addSSHKey',
  '/gpustatus': '/gpu',
  '/gpu_status': '/gpu',
  '/ports': '/port',
  '/systemstatus': '/status',
  '/system_status': '/status',
  '/?': '/help',
};

const SLASH_USAGE_HINTS = {
  '/addSSHKey': '/addSSHKey [username]',
  '/addUser': '/addUser [username]',
  '/docker': '/docker',
  '/gpu': '/gpu',
  '/help': '/help',
  '/mount': '/mount <device> <target> [fstype] [options]',
  '/offload': '/offload <source_abs_path> <target_disk_root_abs_path>',
  '/port': '/port',
  '/status': '/status',
  '/top': '/top cpu|mem [limit 1-30]',
  '/users': '/users',
};

const modelGroups = computed(() => {
  const groups = {};
  for (const [key, config] of Object.entries(props.availableModels || {})) {
    const cat = config.category || 'Other';
    if (!groups[cat]) groups[cat] = [];
    const baseLabel = config.label || key;
    const unavailable = config.available === false;
    groups[cat].push({
      value: key,
      label: unavailable ? `${baseLabel} (⚠️ 目前負載高)` : baseLabel,
    });
  }
  return Object.keys(groups).map(label => ({
    label,
    options: groups[label]
  }));
});

const selectedModelConfig = computed(() => {
  return (props.availableModels || {})[model.value] || null;
});

const suggestedAlternativeKey = computed(() => {
  const cfg = selectedModelConfig.value;
  if (!cfg || cfg.available !== false) return '';
  const alt = typeof cfg.suggestAlternative === 'string' ? cfg.suggestAlternative.trim() : '';
  if (!alt) return '';
  const altConfig = (props.availableModels || {})[alt];
  if (!altConfig || altConfig.available === false) return '';
  return alt;
});

const suggestedAlternativeLabel = computed(() => {
  const key = suggestedAlternativeKey.value;
  if (!key) return '';
  const altConfig = (props.availableModels || {})[key];
  return altConfig?.label || key;
});

const switchToSuggestedAlternative = () => {
  if (!suggestedAlternativeKey.value) return;
  model.value = suggestedAlternativeKey.value;
};

const textareaRef = ref(null);
const isComposing = ref(false);
const isListening = ref(false);
const isFocused = ref(false);
let recognition = null;

const activeSlashIndex = ref(0);
const slashAreaRef = ref(null);
const slashMenuDismissed = ref(false);

// Slash command "picker" parsing:
// - Trigger when the (trimStart) input begins with "/"
// - Allow optional spaces right after "/" ("/ a" should work like "/a")
// - Hide the picker once the user starts typing arguments (whitespace after the command token)
const slashState = computed(() => {
  const v = props.userInput || '';
  const t = v.trimStart();
  if (!t.startsWith('/')) return null;

  const afterSlash = t.slice(1);
  const afterSlashTrimLeft = afterSlash.replace(/^\s+/, '');

  if (!afterSlashTrimLeft) {
    return { query: '', hasArgs: false };
  }

  const m = afterSlashTrimLeft.match(/^(\S+)(\s+.*)?$/);
  const query = (m && m[1]) ? m[1] : '';
  const hasArgs = !!(m && m[2]);
  return { query, hasArgs };
});

const slashItems = computed(() => {
  if (!slashState.value) return [];
  const q = (slashState.value.query || '').toLowerCase();
  const allowed = (props.isAdmin ? SLASH_COMMANDS : SLASH_COMMANDS.filter(x => !ADMIN_ONLY_SLASH_COMMANDS.has(x.cmd)));
  if (!q) return allowed;
  const needle = '/' + q;
  return allowed.filter(x => x.cmd.toLowerCase().startsWith(needle));
});

const activeSlashUsageHint = computed(() => {
  if (!slashState.value) return '';
  const q = (slashState.value.query || '').trim().toLowerCase();
  if (!q) return '';

  const allowed = (props.isAdmin ? SLASH_COMMANDS : SLASH_COMMANDS.filter(x => !ADMIN_ONLY_SLASH_COMMANDS.has(x.cmd)));
  const typed = '/' + q;
  const direct = allowed.find(x => x.cmd.toLowerCase() === typed);
  if (direct) {
    return SLASH_USAGE_HINTS[direct.cmd] || direct.hint || '';
  }

  const canonical = SLASH_COMMAND_ALIASES[typed];
  if (!canonical) return '';
  if (!props.isAdmin && ADMIN_ONLY_SLASH_COMMANDS.has(canonical)) return '';
  return SLASH_USAGE_HINTS[canonical] || '';
});

const showSlashMenu = computed(() => {
  if (props.isProcessing) return false;
  // Only show the picker while the user is still choosing the command token (no args yet).
  return !!slashState.value && !slashState.value.hasArgs && slashItems.value.length > 0;
});

watch(() => slashState.value?.query, () => {
  activeSlashIndex.value = 0;
  slashMenuDismissed.value = false;
});

const isSlashMenuOpen = computed(() => showSlashMenu.value && !slashMenuDismissed.value);

const onGlobalPointerDown = (e) => {
  if (!isSlashMenuOpen.value) return;
  const area = slashAreaRef.value;
  if (!area) return;
  const t = e?.target;
  if (t && area.contains(t)) return;
  slashMenuDismissed.value = true;
};

onMounted(() => {
  // Capture phase ensures we see the event even if inner handlers call preventDefault.
  document.addEventListener('pointerdown', onGlobalPointerDown, true);
  nextTick(adjustHeight);
});

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onGlobalPointerDown, true);
  recognition?.stop();
});

const onCompositionStart = () => {
  isComposing.value = true;
};

const onCompositionEnd = () => {
  setTimeout(() => {
    isComposing.value = false;
  }, 0);
};

const adjustHeight = () => {
  const el = textareaRef.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = el.scrollHeight + 'px';
  el.style.overflowY = el.scrollHeight > el.clientHeight ? 'auto' : 'hidden';
  emit('composer-resize', el.offsetHeight);
};

watch(() => props.userInput, () => {
  nextTick(adjustHeight);
});

const onInput = (e) => {
  emit('update:userInput', e.target.value);
  adjustHeight();
};

const setInput = (v) => {
  emit('update:userInput', v);
  nextTick(() => {
    adjustHeight();
    textareaRef.value?.focus?.();
  });
};

const applySlashCommand = (cmd) => {
  const v = props.userInput || '';
  // Replace the leading slash token (ignoring leading whitespace) with selected cmd.
  // Example: "  /a foo" -> "  /addUser foo"
  let next = v.replace(/^(\s*)\/\s*\S*/, `$1${cmd}`);

  // If it didn't match for some reason, fall back to previous behavior.
  if (next === v) {
    const firstWs = v.search(/\s/);
    next = (firstWs === -1) ? cmd : (cmd + v.slice(firstWs));
  }

  // If user hasn't typed args yet, make it convenient to keep typing.
  // Also makes the slash menu collapse (because we treat trailing whitespace as "args started").
  if (next.trimStart() === cmd) next = next + ' ';
  setInput(next);
};

const onArrowDown = (e) => {
  if (!isSlashMenuOpen.value) return;
  e.preventDefault();
  activeSlashIndex.value = Math.min(activeSlashIndex.value + 1, slashItems.value.length - 1);
};

const onArrowUp = (e) => {
  if (!isSlashMenuOpen.value) return;
  e.preventDefault();
  activeSlashIndex.value = Math.max(activeSlashIndex.value - 1, 0);
};

const onEscape = () => {
  if (!isSlashMenuOpen.value) return;
  slashMenuDismissed.value = true;
  activeSlashIndex.value = 0;
};

const setActiveSlashIndex = (idx) => {
  if (!isSlashMenuOpen.value) return;
  if (typeof idx !== 'number') return;
  activeSlashIndex.value = Math.max(0, Math.min(idx, slashItems.value.length - 1));
};

const openSlashMenu = () => {
  if (props.isProcessing) return;
  slashMenuDismissed.value = false;
  const current = props.userInput || '';
  if (current.length === 0) {
    setInput('/');
    return;
  }
  // Insert "/" at the current cursor position without overwriting existing text.
  const el = textareaRef.value;
  const pos = el ? el.selectionStart ?? current.length : current.length;
  const next = current.slice(0, pos) + '/' + current.slice(pos);
  emit('update:userInput', next);
  nextTick(() => {
    adjustHeight();
    if (el) {
      el.focus();
      el.setSelectionRange(pos + 1, pos + 1);
    }
  });
};

const onEnterPress = (e) => {
  if (e.isComposing || isComposing.value) return;
  const v = props.userInput || '';
  const t = v.trimStart();

  // If user is selecting a slash command (no args yet), Enter should pick the highlighted item.
  if (isSlashMenuOpen.value && t.startsWith('/')) {
    const item = slashItems.value[activeSlashIndex.value] || slashItems.value[0];
    if (item) {
      applySlashCommand(item.cmd);
      return;
    }
  }

  if (!props.isProcessing && props.isOnline !== false && v.trim()) emit('send');
};

const toggleVoice = () => {
  if (isListening.value) {
    recognition?.stop();
    isListening.value = false;
    return;
  }

  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SpeechRecognition) {
    alert("您的瀏覽器不支援語音輸入 (Web Speech API)");
    return;
  }

  recognition = new SpeechRecognition();
  recognition.lang = 'zh-TW';
  recognition.interimResults = true;
  recognition.continuous = false;

  const initialText = props.userInput || '';

  recognition.onstart = () => { isListening.value = true; };

  recognition.onresult = (event) => {
    let transcript = '';
    for (let i = 0; i < event.results.length; ++i) {
      transcript += event.results[i][0].transcript;
    }
    const prefix = initialText ? initialText + ' ' : '';
    emit('update:userInput', prefix + transcript);
  };

  recognition.onend = () => { isListening.value = false; };
  recognition.onerror = (e) => {
    console.error("Speech recognition error", e);
    isListening.value = false;
  };

  recognition.start();
};
</script>

<template>
  <div class="max-w-4xl mx-auto rounded-2xl border overflow-visible shadow-lg transition-all"
       :style="{
         backgroundColor: 'var(--bg-secondary)',
         borderColor: isFocused ? 'var(--border-secondary)' : 'var(--border-primary)',
         boxShadow: isFocused ? '0 0 0 1px var(--border-secondary)' : 'none',
       }"
       @focusin="isFocused = true"
       @focusout="isFocused = false">
    <!-- Top bar: memory toggle + model selector -->
    <div class="flex items-center justify-between px-4 py-2 border-b" style="border-color: var(--border-primary);">
      <div class="flex items-center gap-4">
        <div class="flex flex-col gap-1">
          <!-- Model selector -->
          <select
            v-model="model"
            class="text-xs uppercase border rounded-lg px-2 py-1 cursor-pointer outline-none"
            style="background-color: var(--bg-input); color: var(--text-secondary); border-color: var(--border-primary);"
          >
            <optgroup
              v-for="group in modelGroups"
              :key="group.label"
              :label="group.label"
            >
              <option
                v-for="opt in group.options"
                :key="opt.value"
                :value="opt.value"
                class="py-1"
              >
                {{ opt.label }}
              </option>
            </optgroup>
          </select>
          <div
            v-if="selectedModelConfig && selectedModelConfig.available === false"
            class="text-[11px] font-mono flex items-center gap-2"
            style="color: var(--accent-danger, #ef4444);"
          >
            <span>⚠️ 目前負載高</span>
            <button
              v-if="suggestedAlternativeKey"
              type="button"
              class="underline underline-offset-2"
              style="color: var(--accent-primary);"
              @click="switchToSuggestedAlternative"
            >
              建議切換至 {{ suggestedAlternativeLabel }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Input area -->
    <div class="flex items-end gap-2 px-4 py-3">
      <div ref="slashAreaRef" class="flex-1 relative min-w-0">
        <div
          v-if="isSlashMenuOpen"
          class="absolute left-2 right-2 bottom-full mb-2 rounded-xl border shadow-lg overflow-hidden z-50"
          style="background-color: var(--bg-input); border-color: var(--border-primary);"
        >
          <button
            v-for="(item, idx) in slashItems"
            :key="item.cmd"
            type="button"
            class="w-full text-left px-3 py-2 flex items-center justify-between gap-3 border-b last:border-b-0"
            :style="idx === activeSlashIndex
              ? 'background-color: color-mix(in srgb, var(--accent-primary) 10%, transparent);'
              : ''"
            style="border-color: color-mix(in srgb, var(--border-primary) 70%, transparent);"
            @mouseenter="setActiveSlashIndex(idx)"
            @focus="setActiveSlashIndex(idx)"
            @pointerdown.prevent="applySlashCommand(item.cmd)"
          >
            <div class="flex items-center gap-3 min-w-0">
              <code class="text-xs font-mono px-2 py-1 rounded-lg"
                    style="background-color: color-mix(in srgb, var(--accent-primary) 12%, transparent); color: var(--text-primary);">
                {{ item.cmd }}
              </code>
              <span class="text-xs truncate" style="color: var(--text-secondary);">{{ item.desc }}</span>
            </div>
            <span v-if="item.hint" class="text-[10px] font-mono truncate" style="color: var(--text-tertiary);">
              {{ item.hint }}
            </span>
          </button>
        </div>

        <textarea
          ref="textareaRef"
          :value="userInput"
          @input="onInput"
          @compositionstart="onCompositionStart"
          @compositionend="onCompositionEnd"
          @keydown.enter.exact.prevent="onEnterPress"
          @keydown.down="onArrowDown"
          @keydown.up="onArrowUp"
          @keydown.esc="onEscape"
          :maxlength="maxMessageLength"
          aria-label="輸入訊息"
          class="w-full bg-transparent border-none outline-none py-2 px-2 text-base resize-none leading-relaxed overflow-hidden max-h-[200px]"
          style="color: var(--text-primary);"
          rows="1"
          :placeholder="isListening ? '聽取中...' : '對 server 下些指令'"
        ></textarea>

        <div
          v-if="activeSlashUsageHint"
          class="px-2 pt-1 text-[11px] font-mono whitespace-normal break-words"
          style="color: var(--text-tertiary);"
        >
          提示：{{ activeSlashUsageHint }}
        </div>

        <div
          v-if="userInput.length > maxMessageLength * 0.8"
          class="px-2 pb-1 text-[11px] font-mono text-right"
          :style="{ color: userInput.length >= maxMessageLength ? 'var(--color-danger, #ef4444)' : 'var(--text-tertiary)' }"
        >
          {{ userInput.length }} / {{ maxMessageLength }}
        </div>
      </div>

      <div class="flex-shrink-0 mb-1 flex items-center gap-1">
        <!-- Slash commands button -->
        <Transition name="action-btn" mode="out-in">
          <button
            v-if="!isProcessing && !userInput.trim()"
            key="slash"
            @click="openSlashMenu"
            class="action-btn-base opacity-50 hover:opacity-100"
            style="color: var(--text-secondary);"
            title="Slash commands"
            aria-label="開啟指令選單"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
              <line x1="7" y1="20" x2="17" y2="4" />
            </svg>
          </button>
        </Transition>

        <Transition name="action-btn" mode="out-in">
          <!-- Stop button (processing) -->
          <button v-if="isProcessing" key="stop" @click="emit('stop')"
                  class="action-btn-base"
                  style="color: var(--text-secondary);"
                  title="停止"
                  aria-label="停止生成">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
              <rect x="6" y="6" width="12" height="12" rx="2" />
            </svg>
          </button>

          <!-- Mic button (listening) -->
          <button v-else-if="isListening" key="listening" @click="toggleVoice"
                  class="action-btn-base animate-pulse"
                  style="background-color: color-mix(in srgb, var(--accent-danger) 20%, transparent); color: var(--accent-danger);"
                  title="停止聆聽"
                  aria-label="停止語音輸入">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
            </svg>
          </button>

          <!-- Send button (has text) -->
          <button v-else-if="userInput.trim()" key="send" @click="isOnline !== false && emit('send')"
                  class="action-btn-base"
                  :class="isOnline !== false ? 'action-btn-send' : 'opacity-30 cursor-not-allowed'"
                  :style="isOnline !== false ? 'background-color: var(--accent-primary); color: white;' : 'background-color: var(--bg-tertiary); color: var(--text-tertiary);'"
                  :title="isOnline !== false ? '發送' : '離線中，無法發送'"
                  :aria-label="isOnline !== false ? '發送訊息' : '離線中，無法發送'">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M4.5 10.5 12 3m0 0 7.5 7.5M12 3v18" />
            </svg>
          </button>

          <!-- Mic button (default) -->
          <button v-else key="mic" @click="toggleVoice"
                  class="action-btn-base"
                  style="color: var(--text-tertiary);"
                  title="語音輸入"
                  aria-label="開始語音輸入">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 11a7 7 0 01-7 7m0 0a7 7 0 01-7-7m7 7v4m0 0H8m4 0h4m-4-8a3 3 0 01-3-3V5a3 3 0 116 0v6a3 3 0 01-3 3z" />
            </svg>
          </button>
        </Transition>
      </div>
    </div>

    <!-- Offline banner -->
    <div v-if="isOnline === false" class="px-5 pb-2 text-xs font-mono flex items-center gap-2" style="color: var(--accent-danger, #ef4444);">
      <span>&#9888; 伺服器離線，無法發送訊息</span>
    </div>

    <!-- Status message -->
    <div v-if="statusMessage" class="px-5 pb-2 text-xs font-mono flex items-center gap-2" style="color: var(--accent-secondary);">
      <span class="animate-pulse"><span class="mr-1">&#9889;</span>{{ statusMessage }}</span>
    </div>
  </div>
</template>

<style scoped>
textarea {
  -ms-overflow-style: none;
  scrollbar-width: none;
  overflow-wrap: anywhere;
  word-break: break-word;
}
textarea::-webkit-scrollbar {
  display: none;
}
textarea::placeholder {
  color: var(--text-tertiary);
}
</style>

<style>
/* Button base (no transition-all to avoid conflicting with Vue Transition) */
.action-btn-base {
  padding: 0.625rem;
  border-radius: 0.75rem;
  cursor: pointer;
  border: none;
  background: transparent;
}
.action-btn-send:hover {
  transform: scale(1.05);
}

/* Vue Transition classes — must be unscoped & use !important to beat any utility classes */
.action-btn-enter-active,
.action-btn-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease !important;
}
.action-btn-enter-from {
  opacity: 0 !important;
  transform: scale(0.75) !important;
}
.action-btn-leave-to {
  opacity: 0 !important;
  transform: scale(0.75) !important;
}
</style>
