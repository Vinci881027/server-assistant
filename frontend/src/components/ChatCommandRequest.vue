<script setup>
import { computed, nextTick, ref, watch, onUnmounted } from 'vue';
import { COMMAND_CONFIRM_TIMEOUT_SECONDS } from '../config/app.config';

const DEFAULT_TTL_SECONDS = COMMAND_CONFIRM_TIMEOUT_SECONDS;
const COUNTDOWN_WARN_THRESHOLD_SECONDS = 60;
const COUNTDOWN_DANGER_THRESHOLD_SECONDS = 30;
const EXPIRES_AT_FORMATTER = new Intl.DateTimeFormat('zh-TW', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

const props = defineProps({
  command: { type: String, required: true },
  status: { type: String, default: 'pending' },
  disabled: { type: Boolean, default: false },
  createdAt: { type: Number, default: null },
  resolvedAt: { type: Number, default: null },
  expiresAt: { type: Number, default: null },
  ttlSeconds: { type: Number, default: COMMAND_CONFIRM_TIMEOUT_SECONDS },
})

const emit = defineEmits(['confirm', 'cancel'])

const isPending = computed(() => props.status === 'pending');
const isConfirmed = computed(() => props.status === 'confirmed');
const isCancelled = computed(() => props.status === 'cancelled');
const isExpired = computed(() => isPending.value && remainingSeconds.value <= 0);
const isResolved = computed(() => isConfirmed.value || isCancelled.value || isExpired.value);

const isAlreadyResolved = props.status === 'confirmed' || props.status === 'cancelled' || isInitiallyExpired(props);
const collapsed = ref(isAlreadyResolved);
const summaryRef = ref(null);
const resolvedAt = ref(null);
const resolvedAtIsCreatedFallback = ref(false);
const copied = ref(false);
const copyFailed = ref(false);

const effectiveTtlSeconds = computed(() => {
  if (Number.isFinite(props.ttlSeconds) && props.ttlSeconds > 0) {
    return Math.floor(props.ttlSeconds);
  }
  return DEFAULT_TTL_SECONDS;
});

const deadlineMs = computed(() => {
  if (Number.isFinite(props.expiresAt)) {
    return props.expiresAt;
  }
  if (Number.isFinite(props.createdAt)) {
    return props.createdAt + (effectiveTtlSeconds.value * 1000);
  }
  return null;
});

const hasDeadline = computed(() => Number.isFinite(deadlineMs.value));
const remainingSeconds = ref(isInitiallyExpired(props) ? 0 : effectiveTtlSeconds.value);
let countdownInterval = null;
let collapseTimer = null;

function isInitiallyExpired(currentProps) {
  if (currentProps.status !== 'pending') return false;
  if (Number.isFinite(currentProps.expiresAt)) {
    return currentProps.expiresAt <= Date.now();
  }
  if (Number.isFinite(currentProps.createdAt)) {
    const ttl = Number.isFinite(currentProps.ttlSeconds) && currentProps.ttlSeconds > 0
      ? Math.floor(currentProps.ttlSeconds)
      : DEFAULT_TTL_SECONDS;
    return (currentProps.createdAt + (ttl * 1000)) <= Date.now();
  }
  return false;
}

function stopCountdown() {
  if (!countdownInterval) return;
  clearInterval(countdownInterval);
  countdownInterval = null;
}

function scheduleCollapse(delayMs = 0) {
  if (collapseTimer) {
    clearTimeout(collapseTimer);
    collapseTimer = null;
  }
  collapseTimer = setTimeout(() => {
    collapsed.value = true;
    collapseTimer = null;
  }, delayMs);
}

function updateCountdown() {
  if (!hasDeadline.value) {
    remainingSeconds.value = effectiveTtlSeconds.value;
    return;
  }
  const remainingMs = deadlineMs.value - Date.now();
  remainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1000));
  if (remainingSeconds.value <= 0) {
    stopCountdown();
  }
}

function startCountdown() {
  stopCountdown();
  if (props.status !== 'pending') return;
  updateCountdown();
  if (hasDeadline.value && remainingSeconds.value > 0) {
    countdownInterval = setInterval(updateCountdown, 1000);
  }
}

onUnmounted(() => {
  stopCountdown();
  if (collapseTimer) {
    clearTimeout(collapseTimer);
    collapseTimer = null;
  }
});

watch(
  () => [props.status, props.createdAt, props.expiresAt, props.ttlSeconds],
  ([status]) => {
    if (status === 'pending') {
      startCountdown();
      return;
    }
    stopCountdown();
  },
  { immediate: true }
);

const RESOLVED_AT_FORMATTER = new Intl.DateTimeFormat('zh-TW', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

watch(
  () => [props.status, props.resolvedAt, props.createdAt],
  (next, previous) => {
    const [newStatus, newResolvedAt, newCreatedAt] = next;
    const oldStatus = Array.isArray(previous) ? previous[0] : null;
    if (newStatus !== 'pending') {
      stopCountdown();
    }
    if (newStatus === 'confirmed' || newStatus === 'cancelled') {
      if (Number.isFinite(newResolvedAt)) {
        resolvedAt.value = RESOLVED_AT_FORMATTER.format(new Date(newResolvedAt));
        resolvedAtIsCreatedFallback.value = false;
      } else if (Number.isFinite(newCreatedAt)) {
        resolvedAt.value = RESOLVED_AT_FORMATTER.format(new Date(newCreatedAt));
        resolvedAtIsCreatedFallback.value = true;
      } else if (!resolvedAt.value) {
        resolvedAt.value = RESOLVED_AT_FORMATTER.format(new Date());
        resolvedAtIsCreatedFallback.value = false;
      }

      // Collapse after a short delay so the user sees the status change
      if (oldStatus === 'pending') {
        scheduleCollapse(1800);
      }
      return;
    }
    if (newStatus === 'pending' && !isExpired.value) {
      resolvedAt.value = null;
    }
    resolvedAtIsCreatedFallback.value = false;
  },
  { immediate: true }
);

watch(isExpired, (expired) => {
  if (!expired) return;
  resolvedAt.value = RESOLVED_AT_FORMATTER.format(
    new Date(hasDeadline.value ? deadlineMs.value : Date.now())
  );
  resolvedAtIsCreatedFallback.value = false;
  scheduleCollapse(0);
}, { immediate: true });

const resolvedTimestampLabel = computed(() => {
  if (isExpired.value) return '逾時時間';
  if (resolvedAtIsCreatedFallback.value) return '建立時間';
  return isConfirmed.value ? '執行時間' : '取消時間';
});
const createdAtLabel = computed(() => {
  if (!Number.isFinite(props.createdAt)) return '';
  return RESOLVED_AT_FORMATTER.format(new Date(props.createdAt));
});

// Show countdown pill when ≤ 60s remain; turn danger red when ≤ 30s
const showCountdown = computed(() => isPending.value && hasDeadline.value && remainingSeconds.value <= COUNTDOWN_WARN_THRESHOLD_SECONDS);
const countdownDanger = computed(() => remainingSeconds.value <= COUNTDOWN_DANGER_THRESHOLD_SECONDS);
const expiresAtLabel = computed(() => {
  if (!hasDeadline.value) return '';
  return EXPIRES_AT_FORMATTER.format(new Date(deadlineMs.value));
});
const remainingLabel = computed(() => formatRemaining(remainingSeconds.value));

function formatRemaining(s) {
  if (s <= 0) return '已逾時';
  if (s >= 60) return `${Math.floor(s / 60)}分${String(s % 60).padStart(2, '0')}秒`;
  return `${s} 秒`;
}

const RISK_REASONS = {
  rm:       '永久刪除檔案，無法復原',
  rmdir:    '永久刪除目錄，無法復原',
  mv:       '移動或覆蓋現有檔案',
  cp:       '複製並可能覆蓋大量資料',
  rsync:    '同步並可能覆蓋目標資料',
  tar:      '封存或解壓縮大量資料',
  zip:      '封存大量資料',
  unzip:    '解壓縮並可能覆蓋現有檔案',
  chmod:    '變更檔案存取權限',
  chown:    '變更檔案擁有者',
  useradd:  '新增系統使用者',
  userdel:  '刪除系統使用者',
  usermod:  '修改系統使用者帳號',
  groupadd: '新增系統群組',
  groupdel: '刪除系統群組',
  passwd:   '變更使用者密碼',
  mount:    '掛載磁碟裝置',
  umount:   '卸載磁碟裝置',
  crontab:  '修改排程任務',
  apt:      '安裝或移除系統套件',
  yum:      '安裝或移除系統套件',
  dnf:      '安裝或移除系統套件',
};

const riskReason = computed(() => {
  if (!props.command) return null;
  const words = props.command.trim().split(/\s+/);
  const first = words[0].toLowerCase();
  const cmd = first === 'sudo' ? (words[1] || '').toLowerCase() : first;
  return RISK_REASONS[cmd] || null;
});

async function copyCommand() {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(props.command);
    } else {
      const ta = document.createElement('textarea');
      ta.value = props.command;
      ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none;left:-9999px';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 2000);
  } catch {
    copyFailed.value = true;
    setTimeout(() => { copyFailed.value = false; }, 2000);
  }
}

function collapseDetails() {
  if (!isResolved.value) return;
  collapsed.value = true;
  nextTick(() => summaryRef.value?.focus());
}
</script>

<template>
  <!-- ── Collapsed summary (post-resolution) ── -->
    <Transition name="cmd-collapse">
      <button v-if="collapsed"
              ref="summaryRef"
            type="button"
            @click="collapsed = false"
            class="cmd-summary-row"
            :style="{
              borderColor: isConfirmed ? 'var(--accent-success)' : isExpired ? 'var(--accent-danger)' : 'var(--border-secondary)',
            }"
            :aria-label="isConfirmed ? '指令已執行，點擊展開詳情' : isExpired ? '確認已逾時，點擊展開詳情' : '操作已取消，點擊展開詳情'">
      <!-- Status icon + label -->
      <span class="cmd-summary-status"
            :style="{ color: isConfirmed ? 'var(--accent-success)' : isExpired ? 'var(--accent-danger)' : 'var(--text-tertiary)' }">
        <span v-if="isConfirmed">&#9989;</span>
        <span v-else-if="isExpired">&#9200;</span>
        <span v-else>&#128683;</span>
        <span class="cmd-summary-label">{{ isConfirmed ? '已執行' : isExpired ? '已逾時' : '已取消' }}</span>
      </span>
      <!-- Command preview -->
      <code class="cmd-summary-cmd">{{ command }}</code>
      <!-- Chevron expand -->
      <svg class="cmd-summary-chevron" xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </button>
    </Transition>

    <!-- ── Full card ── -->
    <Transition name="cmd-card">
      <div v-if="!collapsed"
           role="group"
           :aria-label="isExpired ? '確認已逾時' : isPending ? '高風險指令確認' : isConfirmed ? '指令已確認執行' : '操作已取消'"
           class="rounded-xl p-3 border transition-all"
           :style="{
             backgroundColor: 'var(--bg-input)',
             borderColor: isExpired ? 'var(--accent-danger)' : isPending ? 'var(--accent-warning)' : isConfirmed ? 'var(--accent-success)' : 'var(--border-secondary)',
           }">

        <!-- Header row -->
        <component
          :is="isResolved ? 'button' : 'div'"
          class="cmd-card-header flex w-full items-center gap-2 font-semibold text-sm mb-2"
          :class="{ 'cmd-card-header-collapsible': isResolved }"
          :type="isResolved ? 'button' : undefined"
          :aria-label="isResolved ? '收合詳情' : undefined"
          :role="isResolved ? undefined : 'status'"
          :aria-live="isResolved ? undefined : 'polite'"
          :style="{ color: isExpired ? 'var(--accent-danger)' : isPending ? 'var(--accent-warning)' : isConfirmed ? 'var(--accent-success)' : 'var(--text-tertiary)' }"
          @click="collapseDetails">
          <span v-if="isExpired">&#9200;</span>
          <span v-else-if="isPending">&#9888;&#65039;</span>
          <span v-else-if="isConfirmed">&#9989;</span>
          <span v-else>&#128683;</span>
          <span v-if="isExpired">確認已逾時</span>
          <span v-else-if="isPending">系統變更確認</span>
          <span v-else-if="isConfirmed">指令已確認執行</span>
          <span v-else>操作已取消</span>
          <!-- Countdown pill -->
          <span v-if="showCountdown && !isExpired"
                class="ml-auto text-[11px] font-mono px-2 py-0.5 rounded-full countdown-pill"
                :class="countdownDanger ? 'countdown-danger' : 'countdown-warn'"
                aria-live="polite">
            {{ formatRemaining(remainingSeconds) }}
          </span>
          <span v-if="isResolved"
                class="cmd-collapse-indicator ml-auto"
                aria-hidden="true">
            <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="18 15 12 9 6 15"/>
            </svg>
          </span>
        </component>

        <div v-if="isPending && hasDeadline" class="mb-2 text-[11px] font-mono" style="color: var(--text-tertiary);">
          剩餘時間：{{ remainingLabel }} ｜ 到期時間：{{ expiresAtLabel }}
        </div>
        <!-- Resolved timestamp -->
        <div v-if="(isConfirmed || isCancelled || isExpired) && resolvedAt" class="mb-2 text-[11px] font-mono" style="color: var(--text-tertiary);">
          {{ resolvedTimestampLabel }}：{{ resolvedAt }}
        </div>
        <div v-if="createdAtLabel && !resolvedAtIsCreatedFallback" class="mb-2 text-[11px] font-mono" style="color: var(--text-tertiary);">
          建立時間：{{ createdAtLabel }}
        </div>

        <!-- Risk explanation (pending and not expired) -->
        <div v-if="isPending && !isExpired" class="mb-2 text-xs" style="color: var(--text-secondary);">
          AI 請求執行以下高風險指令：
        </div>
        <div v-if="riskReason" class="mb-2 text-xs" style="color: var(--text-secondary);">
          高風險說明：{{ riskReason }}
        </div>
        <!-- Expired explanation -->
        <div v-if="isExpired" class="mb-2 text-xs" style="color: var(--text-secondary);">
          確認視窗已過期，請重新發送指令。
        </div>

        <!-- Code block with copy button -->
        <div class="rounded-lg border overflow-hidden" :style="{ borderColor: 'var(--border-primary)' }">
          <div class="flex items-center justify-between px-3 py-1"
               style="background-color: color-mix(in srgb, var(--code-bg) 85%, var(--text-tertiary) 15%); border-bottom: 1px solid var(--border-primary); min-height: 30px;">
            <span class="text-[10px] font-semibold uppercase tracking-wide font-mono" style="color: var(--text-tertiary);">shell</span>
            <button
              type="button"
              @click.stop="copyCommand"
              class="cmd-copy-btn"
              :class="{ 'cmd-copy-btn-copied': copied, 'cmd-copy-btn-error': copyFailed }"
              title="複製指令"
            >
              <!-- default: copy icon -->
              <svg xmlns="http://www.w3.org/2000/svg" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
              </svg>
            </button>
          </div>
          <pre class="p-3 overflow-x-auto text-sm font-mono m-0 border-0 rounded-none"
               :style="{
                 backgroundColor: 'var(--code-bg)',
                 color: isPending ? 'var(--accent-warning)' : isConfirmed ? 'var(--accent-success)' : 'var(--text-tertiary)'
               }"><code>{{ command }}</code></pre>
        </div>

        <!-- Action buttons (pending only) -->
        <div v-if="isPending && !isExpired" class="flex justify-end gap-2 mt-3">
          <button @click="emit('cancel')"
                  :disabled="disabled"
                  class="px-3 py-1.5 rounded-lg text-xs font-medium border transition-all"
                  :style="{
                    backgroundColor: 'transparent',
                    borderColor: 'var(--border-secondary)',
                    color: 'var(--text-secondary)',
                    opacity: disabled ? 0.5 : 1,
                    cursor: disabled ? 'not-allowed' : 'pointer'
                  }">
            取消
          </button>
          <button @click="emit('confirm', command)"
                  :disabled="disabled"
                  class="px-3 py-1.5 rounded-lg text-xs font-semibold transition-all"
                  :style="{
                    backgroundColor: 'var(--accent-warning)',
                    color: '#000',
                    opacity: disabled ? 0.5 : 1,
                    cursor: disabled ? 'not-allowed' : 'pointer'
                  }">
            {{ disabled ? '處理中...' : '確認執行' }}
          </button>
        </div>
      </div>
    </Transition>
</template>

<style scoped>
/* Countdown pill */
.countdown-pill {
  letter-spacing: 0.01em;
  font-weight: 600;
  transition: background-color 0.3s, color 0.3s;
}
.countdown-warn {
  background-color: color-mix(in srgb, var(--accent-warning) 15%, transparent);
  color: var(--accent-warning);
}
.countdown-danger {
  background-color: color-mix(in srgb, var(--accent-danger) 15%, transparent);
  color: var(--accent-danger);
  animation: pulse-danger 1s ease-in-out infinite;
}
@keyframes pulse-danger {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

/* Copy button in command card header */
.cmd-copy-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 0.375rem;
  background: transparent;
  color: var(--text-tertiary);
  cursor: pointer;
  transition: color 0.15s, background-color 0.15s;
}
.cmd-copy-btn:hover {
  color: var(--text-primary);
  background-color: color-mix(in srgb, var(--text-tertiary) 15%, transparent);
}
.cmd-copy-btn svg { display: block; }
.cmd-copy-btn-copied svg,
.cmd-copy-btn-error svg { display: none; }
.cmd-copy-btn-copied {
  color: var(--accent-success) !important;
  pointer-events: none;
}
.cmd-copy-btn-copied::after {
  content: '';
  display: block;
  width: 13px;
  height: 13px;
  background: currentColor;
  -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='13' height='13' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6L9 17l-5-5'/%3E%3C/svg%3E") no-repeat center;
  mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='13' height='13' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='3' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M20 6L9 17l-5-5'/%3E%3C/svg%3E") no-repeat center;
}
.cmd-copy-btn-error {
  color: var(--accent-danger) !important;
  pointer-events: none;
  width: auto !important;
  padding: 0 6px !important;
  font-size: 10px;
  font-weight: 600;
}
.cmd-copy-btn-error::after {
  content: '複製失敗';
  display: block;
  white-space: nowrap;
  line-height: 1;
}

/* Collapsed summary row */
.cmd-summary-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 5px 10px;
  border-radius: 8px;
  border: 1px solid var(--border-secondary);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background-color 0.15s;
  min-width: 0;
}
.cmd-summary-row:hover {
  background-color: color-mix(in srgb, var(--text-tertiary) 8%, transparent);
}
.cmd-summary-status {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.cmd-summary-label {
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}
.cmd-summary-cmd {
  font-size: 11px;
  font-family: ui-monospace, monospace;
  color: var(--text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
  min-width: 0;
}
.cmd-summary-chevron {
  color: var(--text-tertiary);
  flex-shrink: 0;
}

/* Collapsible header in expanded card */
.cmd-card-header {
  border: none;
  background: transparent;
  padding: 0;
  text-align: left;
}
.cmd-card-header-collapsible {
  cursor: pointer;
  border-radius: 6px;
}
.cmd-card-header-collapsible:is(:hover, :focus-visible) {
  background-color: color-mix(in srgb, var(--text-tertiary) 10%, transparent);
}
.cmd-card-header-collapsible:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent-warning) 60%, transparent);
  outline-offset: 2px;
}
.cmd-collapse-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-tertiary);
}

/* Card enter/leave transitions */
.cmd-card-enter-active,
.cmd-card-leave-active {
  transition: opacity 0.25s ease, max-height 0.3s ease;
  overflow: hidden;
  max-height: 400px;
}
.cmd-card-enter-from,
.cmd-card-leave-to {
  opacity: 0;
  max-height: 0;
}

/* Summary enter/leave transitions */
.cmd-collapse-enter-active,
.cmd-collapse-leave-active {
  transition: opacity 0.2s ease, max-height 0.25s ease;
  overflow: hidden;
  max-height: 48px;
}
.cmd-collapse-enter-from,
.cmd-collapse-leave-to {
  opacity: 0;
  max-height: 0;
}
</style>
