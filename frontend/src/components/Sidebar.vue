<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue';

const props = defineProps({
  isOpen: {
    type: Boolean,
    required: true
  },
  conversations: {
    type: Array,
    default: () => []
  },
  currentId: {
    type: String,
    default: ''
  }
});
const emit = defineEmits(['new-chat', 'select-chat', 'delete-chat', 'export-chat']);

const searchQuery = ref('');
const searchInputRef = ref(null);
const isTouchDevice = ref(window.matchMedia('(hover: none)').matches);
const pendingDeleteId = ref('');
const openActionMenuId = ref('');
let resetDeleteIntentTimer = null;

// ========== Pin state (localStorage) ==========
const PINNED_KEY = 'sidebar_pinned_conversations';
const pinnedIds = ref(new Set(JSON.parse(localStorage.getItem(PINNED_KEY) || '[]')));

function savePinned() {
  localStorage.setItem(PINNED_KEY, JSON.stringify([...pinnedIds.value]));
}

function togglePin(chatId) {
  closeActionMenu();
  const next = new Set(pinnedIds.value);
  if (next.has(chatId)) {
    next.delete(chatId);
  } else {
    next.add(chatId);
  }
  pinnedIds.value = next;
  savePinned();
}

// ========== Date grouping ==========

function getDateGroup(isoString) {
  if (!isoString) return '更早';
  const date = new Date(isoString);
  if (isNaN(date.getTime())) return '更早';
  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterdayStart = new Date(todayStart);
  yesterdayStart.setDate(yesterdayStart.getDate() - 1);
  const sevenDaysAgo = new Date(todayStart);
  sevenDaysAgo.setDate(sevenDaysAgo.getDate() - 6);
  if (date >= todayStart) return '今天';
  if (date >= yesterdayStart) return '昨天';
  if (date >= sevenDaysAgo) return '本週';
  return '更早';
}

const DATE_GROUP_ORDER = ['今天', '昨天', '本週', '更早'];

const groupedConversations = computed(() => {
  const q = searchQuery.value.trim().toLowerCase();
  const all = props.conversations;

  if (q) {
    const filtered = all.filter(c => c.title?.toLowerCase().includes(q));
    return [{ label: `找到 ${filtered.length} 個`, items: filtered, isSearch: true }];
  }

  const pinned = all.filter(c => pinnedIds.value.has(c.id));
  const unpinned = all.filter(c => !pinnedIds.value.has(c.id));

  const groups = [];
  if (pinned.length > 0) {
    groups.push({ label: '已釘選', items: pinned, isPinned: true });
  }

  const dateGroupMap = new Map();
  for (const c of unpinned) {
    const grp = getDateGroup(c.updatedAt);
    if (!dateGroupMap.has(grp)) dateGroupMap.set(grp, []);
    dateGroupMap.get(grp).push(c);
  }
  for (const label of DATE_GROUP_ORDER) {
    if (dateGroupMap.has(label)) {
      groups.push({ label, items: dateGroupMap.get(label) });
    }
  }

  return groups;
});

const hasNoSearchResults = computed(() => {
  return searchQuery.value.trim() !== '' &&
    groupedConversations.value.length > 0 &&
    groupedConversations.value[0].items.length === 0;
});

// ========== Action menu / delete ==========

function clearDeleteIntent() {
  if (resetDeleteIntentTimer) {
    clearTimeout(resetDeleteIntentTimer);
    resetDeleteIntentTimer = null;
  }
  pendingDeleteId.value = '';
}

function closeActionMenu() {
  openActionMenuId.value = '';
}

function toggleActionMenu(chatId) {
  openActionMenuId.value = openActionMenuId.value === chatId ? '' : chatId;
}

function startDeleteIntent(chatId) {
  clearDeleteIntent();
  closeActionMenu();
  pendingDeleteId.value = chatId;
  resetDeleteIntentTimer = setTimeout(() => {
    pendingDeleteId.value = '';
    resetDeleteIntentTimer = null;
  }, 2500);
}

function handleSelectChat(chatId) {
  clearDeleteIntent();
  closeActionMenu();
  emit('select-chat', chatId);
}

function handleChatKeyboardSelect(event, chatId) {
  if (event.target !== event.currentTarget) {
    return;
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    handleSelectChat(chatId);
  }
}

function handleDeleteClick(chatId) {
  closeActionMenu();
  if (!isTouchDevice.value) {
    emit('delete-chat', chatId);
    return;
  }

  if (pendingDeleteId.value === chatId) {
    clearDeleteIntent();
    emit('delete-chat', chatId);
    return;
  }

  startDeleteIntent(chatId);
}

function handleExportClick(chatId, format) {
  closeActionMenu();
  emit('export-chat', { chatId, format });
}

function handleGlobalPointerDown(event) {
  if (!(event.target instanceof Element)) {
    closeActionMenu();
    return;
  }

  if (event.target.closest('[data-sidebar-chat-actions]')) {
    return;
  }

  closeActionMenu();
}

function handleGlobalKeydown(event) {
  if (event.key === 'Escape') {
    closeActionMenu();
  }
}

async function focusSearchInput() {
  await nextTick();
  if (!searchInputRef.value) return;
  searchInputRef.value.focus();
  searchInputRef.value.select();
}

watch(
  () => props.conversations.map((chat) => chat.id),
  (conversationIds) => {
    if (pendingDeleteId.value && !conversationIds.includes(pendingDeleteId.value)) {
      clearDeleteIntent();
    }
    if (openActionMenuId.value && !conversationIds.includes(openActionMenuId.value)) {
      closeActionMenu();
    }
    // Clean up pinned IDs for deleted conversations
    const idSet = new Set(conversationIds);
    const next = new Set([...pinnedIds.value].filter(id => idSet.has(id)));
    if (next.size !== pinnedIds.value.size) {
      pinnedIds.value = next;
      savePinned();
    }
  }
);

onMounted(() => {
  window.addEventListener('pointerdown', handleGlobalPointerDown);
  window.addEventListener('keydown', handleGlobalKeydown);
});

onBeforeUnmount(() => {
  clearDeleteIntent();
  window.removeEventListener('pointerdown', handleGlobalPointerDown);
  window.removeEventListener('keydown', handleGlobalKeydown);
});

defineExpose({
  focusSearchInput,
});

function formatRelativeTime(isoString) {
  if (!isoString) return '';
  const date = new Date(isoString);
  if (isNaN(date.getTime())) return '';
  const diffMs = Date.now() - date.getTime();
  const diffSec = Math.floor(diffMs / 1000);
  if (diffSec < 60) return 'now';
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h`;
  const diffDay = Math.floor(diffHr / 24);
  if (diffDay < 30) return `${diffDay}d`;
  const diffMon = Math.floor(diffDay / 30);
  if (diffMon < 12) return `${diffMon}mo`;
  return `${Math.floor(diffMon / 12)}y`;
}
</script>

<template>
  <div
    class="h-full border-r transition-all duration-300 ease-in-out flex flex-col shrink-0 overflow-hidden"
    :class="isOpen ? 'w-[260px] opacity-100' : 'w-0 opacity-0 border-none'"
    style="background-color: var(--bg-secondary); border-color: var(--border-primary);"
    :inert="!isOpen"
    :aria-hidden="!isOpen"
  >
    <!-- New chat button -->
    <div class="p-3 flex flex-col gap-2">
      <button
        @click="$emit('new-chat')"
        class="w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-xl transition-all text-left hover:scale-[1.02]"
        style="background-color: var(--bg-tertiary); color: var(--text-primary);"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
        <span>新對話</span>
      </button>

      <!-- Search input -->
      <div class="relative">
        <svg xmlns="http://www.w3.org/2000/svg" class="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 pointer-events-none" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
        </svg>
        <input
          ref="searchInputRef"
          v-model="searchQuery"
          type="text"
          placeholder="搜尋對話..."
          class="w-full pl-8 pr-3 py-1.5 text-xs rounded-lg outline-none transition-all"
          style="background-color: var(--bg-tertiary); color: var(--text-primary); border: 1px solid var(--border-primary);"
        />
      </div>
    </div>

    <!-- Conversation list -->
    <div class="flex-1 overflow-y-auto px-2 py-1 custom-scrollbar">

      <!-- Empty state: no conversations at all -->
      <div v-if="conversations.length === 0 && !searchQuery.trim()" class="px-3 py-8 flex flex-col items-center gap-3 text-center">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-10 w-10 opacity-30" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
        </svg>
        <div>
          <p class="text-xs font-medium mb-1" style="color: var(--text-secondary);">尚無任何對話</p>
          <p class="text-[11px] leading-relaxed" style="color: var(--text-tertiary);">點擊上方「新對話」按鈕<br>開始與 AI 互動</p>
        </div>
      </div>

      <!-- Empty state: search no results -->
      <div v-else-if="hasNoSearchResults" class="px-3 py-8 flex flex-col items-center gap-3 text-center">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-10 w-10 opacity-30" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M21 21l-4.35-4.35M17 11A6 6 0 1 1 5 11a6 6 0 0 1 12 0z" />
        </svg>
        <div>
          <p class="text-xs font-medium mb-1" style="color: var(--text-secondary);">找不到符合的對話</p>
          <p class="text-[11px] leading-relaxed" style="color: var(--text-tertiary);">試試其他關鍵字</p>
        </div>
      </div>

      <!-- Grouped conversation list -->
      <template v-else v-for="group in groupedConversations" :key="group.label">
        <!-- Group header -->
        <div class="flex items-center gap-1.5 px-3 pt-3 pb-1">
          <!-- Pin icon for pinned group -->
          <svg v-if="group.isPinned" xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 shrink-0" viewBox="0 0 24 24" fill="currentColor" style="color: var(--accent-primary);">
            <path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5v6h2v-6h5v-2l-2-2z"/>
          </svg>
          <span class="text-[10px] font-semibold uppercase tracking-widest" style="color: var(--text-tertiary);">{{ group.label }}</span>
        </div>

        <!-- Chat items in group -->
        <div
          v-for="chat in group.items"
          :key="chat.id"
          @click="handleSelectChat(chat.id)"
          @keydown="handleChatKeyboardSelect($event, chat.id)"
          class="chat-item group flex items-center justify-between px-3 py-2 mb-0.5 text-sm rounded-lg cursor-pointer transition-all"
          :class="{
            'chat-item--active': currentId === chat.id,
            'chat-item--pinned': pinnedIds.has(chat.id) && !searchQuery.trim()
          }"
          role="button"
          tabindex="0"
        >
          <div class="flex-1 min-w-0 mr-2">
            <div class="truncate text-sm">{{ chat.title }}</div>
            <div class="flex items-center gap-1.5 mt-0.5" style="color: var(--text-tertiary);">
              <span class="text-[10px]" v-if="chat.updatedAt">{{ formatRelativeTime(chat.updatedAt) }}</span>
              <span class="text-[10px]" v-if="chat.updatedAt && chat.messageCount">·</span>
              <span class="text-[10px]" v-if="chat.messageCount">{{ chat.messageCount }} 則</span>
            </div>
            <div
              v-if="isTouchDevice && pendingDeleteId === chat.id"
              class="text-[10px] mt-0.5 font-medium"
              style="color: var(--accent-danger);"
            >
              再按一次刪除
            </div>
          </div>
          <div class="relative flex items-center gap-1" data-sidebar-chat-actions>
            <button
              @click.stop="toggleActionMenu(chat.id)"
              :class="[
                isTouchDevice ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
                'sidebar-menu-button p-1 rounded transition-all'
              ]"
              title="對話選單"
              aria-label="對話選單"
              :aria-expanded="openActionMenuId === chat.id"
              aria-haspopup="menu"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 12h.01M12 12h.01M18 12h.01" />
              </svg>
            </button>
            <div
              v-if="openActionMenuId === chat.id"
              class="sidebar-action-menu absolute right-0 top-7 z-30 rounded-lg shadow-lg border overflow-hidden min-w-[168px]"
              style="background-color: var(--bg-secondary); border-color: var(--border-primary);"
              role="menu"
              @click.stop
            >
              <!-- Pin / Unpin -->
              <button
                class="w-full text-left px-3 py-2 text-xs transition-colors flex items-center gap-2"
                style="color: var(--text-secondary);"
                @click.stop="togglePin(chat.id)"
                role="menuitem"
              >
                <svg v-if="pinnedIds.has(chat.id)" xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 shrink-0" viewBox="0 0 24 24" fill="currentColor" style="color: var(--accent-primary);">
                  <path d="M16 12V4h1V2H7v2h1v8l-2 2v2h5v6h2v-6h5v-2l-2-2z"/>
                </svg>
                <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 12V4h1V2H7v2h1v8l-2 2v2h5v6h2v-6h5v-2l-2-2z"/>
                </svg>
                {{ pinnedIds.has(chat.id) ? '取消釘選' : '釘選對話' }}
              </button>
              <div class="border-t" style="border-color: var(--border-primary);"></div>
              <button
                class="w-full text-left px-3 py-2 text-xs transition-colors"
                style="color: var(--text-secondary);"
                @click.stop="handleExportClick(chat.id, 'markdown')"
                role="menuitem"
              >
                匯出為 Markdown
              </button>
              <button
                class="w-full text-left px-3 py-2 text-xs transition-colors"
                style="color: var(--text-secondary);"
                @click.stop="handleExportClick(chat.id, 'json')"
                role="menuitem"
              >
                匯出為 JSON
              </button>
            </div>
            <button
              @click.stop="handleDeleteClick(chat.id)"
              :class="[
                isTouchDevice ? 'opacity-100' : 'opacity-0 group-hover:opacity-100 group-focus-within:opacity-100',
                'sidebar-delete-button p-1 rounded transition-all',
                { 'sidebar-delete-button--pending': pendingDeleteId === chat.id }
              ]"
              :title="pendingDeleteId === chat.id
                ? `再按一次刪除：${chat.title}`
                : `刪除對話：${chat.title}`"
              :aria-label="pendingDeleteId === chat.id
                ? `再按一次刪除：${chat.title}`
                : `刪除對話：${chat.title}`"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
              </svg>
            </button>
          </div>
        </div>
      </template>
    </div>

    <!-- Footer -->
    <div class="p-3 border-t" style="border-color: var(--border-primary);">
      <div class="flex items-center gap-2 text-[10px]" style="color: var(--text-tertiary);">
        <span>Server Assistant v1.0</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-item {
  color: var(--text-secondary);
  border-left: 2px solid transparent;
}

.chat-item--active {
  background-color: color-mix(in srgb, var(--accent-primary) 15%, transparent);
  color: var(--accent-primary-hover);
  border-left-color: var(--accent-primary);
}

.chat-item--pinned:not(.chat-item--active) {
  border-left-color: color-mix(in srgb, var(--accent-primary) 40%, transparent);
}

.chat-item:not(.chat-item--active):is(:hover, :focus-visible) {
  background-color: var(--bg-tertiary);
}

.chat-item:focus-visible,
.sidebar-delete-button:focus-visible,
.sidebar-menu-button:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--accent-primary) 55%, transparent);
  outline-offset: 1px;
}

.sidebar-delete-button {
  color: var(--text-tertiary);
}

.sidebar-menu-button {
  color: var(--text-tertiary);
}

.sidebar-menu-button:is(:hover, :focus-visible) {
  color: var(--text-primary);
}

.sidebar-delete-button--pending {
  color: var(--accent-danger);
  background-color: color-mix(in srgb, var(--accent-danger) 14%, transparent);
}

.sidebar-delete-button:not(.sidebar-delete-button--pending):is(:hover, :focus-visible) {
  color: var(--accent-danger);
}

.sidebar-action-menu button:is(:hover, :focus-visible) {
  background-color: var(--bg-tertiary);
}
</style>
