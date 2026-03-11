<script setup>
import { ref, onMounted, onUnmounted, watch, computed } from 'vue';
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
import httpClient from '../api/httpClient'
import { useThemeStore } from '../stores/themeStore'
import { buildCsvText, buildExportTimestamp, downloadTextFile, sanitizeFilenamePart } from '../utils/exportUtils'

const themeStore = useThemeStore()
const emit = defineEmits(['close', 'conversations-updated']);

const md = new MarkdownIt({ html: false, linkify: true, typographer: true });
const RESOLVED_CMD_MARKER_REGEX = /\n?\[RESOLVED_CMD:::(?:confirmed|cancelled):::([\s\S]+?):::\]/g

const renderMarkdown = (text) => {
  if (!text) return '';
  try {
    return DOMPurify.sanitize(md.render(text), {
      FORBID_TAGS: ['style'],
      FORBID_ATTR: ['style', 'onerror', 'onload', 'onclick', 'onmouseover'],
    });
  } catch (err) {
    console.error('Markdown rendering failed:', err);
    return '<p>[內容渲染失敗]</p>';
  }
};

const hideResolvedCmdMarker = (text) => {
  if (typeof text !== 'string' || !text) return text || ''
  return text.replace(RESOLVED_CMD_MARKER_REGEX, '')
}

const users = ref([]);
const selectedUser = ref('');
const activeTab = ref('audit'); // 'audit', 'history', 'models'
const auditLogs = ref([]);
const chatHistory = ref([]);
const DEFAULT_PAGE = 0
const DEFAULT_SIZE = 100
const EXPORT_PAGE_SIZE = 500
const MAX_EXPORT_PAGES = 200
const AUTO_REFRESH_INTERVAL_MS = 30_000
const models = ref([]);
const isLoading = ref(false);
const isPurging = ref(false);
const isExporting = ref(false);
const isManualRefreshing = ref(false);
const isAutoRefreshing = ref(false);
const purgeMessage = ref('');
const exportMessage = ref('');
const isModelSaving = ref(false);
const modelMessage = ref('');
const lastAuditUpdatedAt = ref(null)
const lastHistoryUpdatedAt = ref(null)
let autoRefreshTimerId = null
const auditPage = ref({
  page: DEFAULT_PAGE,
  size: DEFAULT_SIZE,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
})
const historyPage = ref({
  page: DEFAULT_PAGE,
  size: DEFAULT_SIZE,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
})
const newModel = ref({
  id: '',
  label: '',
  name: '',
  tpm: 0,
  category: 'Other',
  enabled: true,
});

// 載入使用者列表
const fetchUsers = async () => {
  try {
    const res = await httpClient.get('/admin/users')
    const payload = Array.isArray(res?.data?.data) ? res.data.data : res?.data
    users.value = Array.isArray(payload) ? payload : []
  } catch (e) { console.error(e); }
};

const applyPageState = (target, payload, fallbackPage) => {
  target.value = {
    page: Number.isFinite(payload?.page) ? payload.page : fallbackPage,
    size: Number.isFinite(payload?.size) ? payload.size : target.value.size,
    totalElements: Number.isFinite(payload?.totalElements) ? payload.totalElements : 0,
    totalPages: Number.isFinite(payload?.totalPages) ? payload.totalPages : 0,
    hasNext: Boolean(payload?.hasNext),
  }
}

const resetPagedData = () => {
  auditLogs.value = []
  chatHistory.value = []
  auditPage.value = { page: DEFAULT_PAGE, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false }
  historyPage.value = { page: DEFAULT_PAGE, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false }
  lastAuditUpdatedAt.value = null
  lastHistoryUpdatedAt.value = null
}

const toMillis = (value) => {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0

  if (Array.isArray(value)) {
    const [y, mo, d, h = 0, mi = 0, s = 0, ns = 0] = value
    if (![y, mo, d].every(Number.isFinite)) return 0
    return Date.UTC(y, mo - 1, d, h, mi, s, Math.floor((Number(ns) || 0) / 1_000_000))
  }

  if (typeof value === 'string') {
    const normalized = value.trim().replace(' ', 'T')
    const direct = Date.parse(normalized)
    if (Number.isFinite(direct)) return direct

    const m = normalized.match(
      /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?$/
    )
    if (m) {
      const [, y, mo, d, h, mi, s = '0', fraction = '0'] = m
      const ms = Number((fraction + '000').slice(0, 3))
      return Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s), ms)
    }
  }

  if (value instanceof Date) {
    const t = value.getTime()
    return Number.isFinite(t) ? t : 0
  }

  return 0
}

const sortAuditLogsNewestFirst = (items) => {
  return [...items].sort((a, b) => {
    const timeDiff = toMillis(b?.executionTime) - toMillis(a?.executionTime)
    if (timeDiff !== 0) return timeDiff
    return Number(b?.id ?? 0) - Number(a?.id ?? 0)
  })
}

const sortedAuditLogs = computed(() => sortAuditLogsNewestFirst(auditLogs.value))

// 載入指令審計紀錄
const fetchAuditLogs = async (page = auditPage.value.page, { silent = false } = {}) => {
  if (!selectedUser.value) {
    auditLogs.value = []
    auditPage.value = { page: DEFAULT_PAGE, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false }
    lastAuditUpdatedAt.value = null
    return
  }
  const safePage = Math.max(page, 0)
  if (!silent) isLoading.value = true;
  try {
    const res = await httpClient.get(`/admin/audit/${encodeURIComponent(selectedUser.value)}`, {
      params: { page: safePage, size: auditPage.value.size }
    })
    const payload = res?.data?.data ?? {}
    const items = Array.isArray(payload.items) ? payload.items : []
    auditLogs.value = sortAuditLogsNewestFirst(items)
    applyPageState(auditPage, payload, safePage)
    lastAuditUpdatedAt.value = Date.now()
  } catch (e) { console.error(e); }
  finally { if (!silent) isLoading.value = false; }
};

// 載入對話歷史
const fetchHistory = async (page = historyPage.value.page, { silent = false } = {}) => {
  if (!selectedUser.value) {
    chatHistory.value = []
    historyPage.value = { page: DEFAULT_PAGE, size: DEFAULT_SIZE, totalElements: 0, totalPages: 0, hasNext: false }
    lastHistoryUpdatedAt.value = null
    return
  }
  const safePage = Math.max(page, 0)
  if (!silent) isLoading.value = true;
  try {
    const res = await httpClient.get(`/admin/history/${encodeURIComponent(selectedUser.value)}`, {
      params: { page: safePage, size: historyPage.value.size }
    })
    const payload = res?.data?.data ?? {}
    chatHistory.value = Array.isArray(payload.items) ? payload.items : []
    applyPageState(historyPage, payload, safePage)
    lastHistoryUpdatedAt.value = Date.now()
  } catch (e) { console.error(e); }
  finally { if (!silent) isLoading.value = false; }
};

// 載入模型列表 (管理功能)
const fetchModels = async () => {
  try {
    const res = await httpClient.get('/admin/models')
    models.value = res?.data || []
  } catch (e) { console.error(e); }
};

const saveModel = async (model) => {
  if (!model?.id) {
    modelMessage.value = 'Model id 不可為空'
    return
  }
  isModelSaving.value = true
  modelMessage.value = ''
  try {
    const payload = {
      ...model,
      tpm: Number(model.tpm) || 0,
      category: model.category || 'Other',
    }
    await httpClient.post('/admin/models', payload)
    modelMessage.value = `已儲存模型：${model.id}`
    await fetchModels()
  } catch (e) {
    console.error(e)
    modelMessage.value = e?.message || '儲存失敗'
  } finally {
    isModelSaving.value = false
  }
}

const addModel = async () => {
  const m = newModel.value
  if (!m.id?.trim() || !m.name?.trim() || !m.label?.trim()) {
    modelMessage.value = '請填寫 id / name / label'
    return
  }
  isModelSaving.value = true
  modelMessage.value = ''
  try {
    const payload = {
      id: m.id.trim(),
      name: m.name.trim(),
      label: m.label.trim(),
      tpm: Number(m.tpm) || 0,
      category: (m.category || 'Other').trim(),
      enabled: !!m.enabled,
    }
    await httpClient.post('/admin/models', payload)
    modelMessage.value = `已新增模型：${payload.id}`
    newModel.value = { id: '', label: '', name: '', tpm: 0, category: 'Other', enabled: true }
    await fetchModels()
  } catch (e) {
    console.error(e)
    modelMessage.value = e?.message || '新增失敗'
  } finally {
    isModelSaving.value = false
  }
}

const deleteModel = async (id) => {
  if (!id) return
  const ok = window.confirm(`確定要刪除模型「${id}」嗎？`)
  if (!ok) return

  isModelSaving.value = true
  modelMessage.value = ''
  try {
    await httpClient.delete(`/admin/models/${encodeURIComponent(id)}`)
    modelMessage.value = `已刪除模型：${id}`
    await fetchModels()
  } catch (e) {
    console.error(e)
    modelMessage.value = e?.message || '刪除失敗'
  } finally {
    isModelSaving.value = false
  }
}

// 監聽選擇變更
watch(selectedUser, () => {
  auditPage.value.page = DEFAULT_PAGE
  historyPage.value.page = DEFAULT_PAGE
  lastAuditUpdatedAt.value = null
  lastHistoryUpdatedAt.value = null
  exportMessage.value = ''
  if (activeTab.value === 'audit') fetchAuditLogs(DEFAULT_PAGE);
  if (activeTab.value === 'history') fetchHistory(DEFAULT_PAGE);
});

watch(activeTab, (newTab) => {
  exportMessage.value = ''
  if (newTab === 'models') {
    fetchModels();
  } else if (selectedUser.value) {
    if (newTab === 'audit') fetchAuditLogs(auditPage.value.page);
    if (newTab === 'history') fetchHistory(historyPage.value.page);
  }
});

const refreshActiveTabData = async ({ silent = false } = {}) => {
  if (!selectedUser.value || activeTab.value === 'models') return
  if (activeTab.value === 'audit') {
    await fetchAuditLogs(auditPage.value.page, { silent })
    return
  }
  if (activeTab.value === 'history') {
    await fetchHistory(historyPage.value.page, { silent })
  }
}

const refreshCurrentView = async () => {
  if (!selectedUser.value || isPurging.value || isLoading.value || isAutoRefreshing.value) return
  isManualRefreshing.value = true
  try {
    await refreshActiveTabData({ silent: false })
  } finally {
    isManualRefreshing.value = false
  }
}

const startAutoRefresh = () => {
  if (autoRefreshTimerId !== null) return
  autoRefreshTimerId = window.setInterval(() => {
    if (!selectedUser.value || activeTab.value === 'models') return
    if (typeof document !== 'undefined' && document.hidden) return
    if (isPurging.value || isLoading.value || isManualRefreshing.value || isAutoRefreshing.value) return

    isAutoRefreshing.value = true
    void refreshActiveTabData({ silent: true }).finally(() => {
      isAutoRefreshing.value = false
    })
  }, AUTO_REFRESH_INTERVAL_MS)
}

const stopAutoRefresh = () => {
  if (autoRefreshTimerId === null) return
  window.clearInterval(autoRefreshTimerId)
  autoRefreshTimerId = null
}

onMounted(() => {
  fetchUsers();
  startAutoRefresh();
});

onUnmounted(() => {
  stopAutoRefresh();
});

const formatDate = (ts) => {
  if (!ts) return '-';
  return new Date(ts).toLocaleString();
};

const isExportTab = computed(() => activeTab.value === 'audit' || activeTab.value === 'history')

const fetchAllPagedItems = async (endpoint) => {
  const allItems = []

  for (let page = 0; page < MAX_EXPORT_PAGES; page += 1) {
    const res = await httpClient.get(endpoint, {
      params: { page, size: EXPORT_PAGE_SIZE }
    })
    const payload = res?.data?.data ?? {}
    const items = Array.isArray(payload.items) ? payload.items : []
    allItems.push(...items)
    if (!payload.hasNext) {
      return allItems
    }
  }

  throw new Error('資料量過大，請縮小範圍後重試')
}

const exportSelectedUserCsv = async () => {
  if (!selectedUser.value || !isExportTab.value || isPurging.value) return

  isExporting.value = true
  exportMessage.value = ''
  try {
    const username = selectedUser.value
    const encodedUser = encodeURIComponent(username)
    let csvHeaders = []
    let csvRows = []
    let label = ''

    if (activeTab.value === 'audit') {
      const items = await fetchAllPagedItems(`/admin/audit/${encodedUser}`)
      csvHeaders = ['seq', 'username', 'execution_time', 'status', 'command_type', 'command', 'exit_code', 'output']
      csvRows = items.map((log, index) => [
        index + 1,
        username,
        log?.executionTime ?? '',
        log?.success ? 'SUCCESS' : 'FAILED',
        log?.commandType ?? '',
        log?.command ?? '',
        log?.exitCode ?? '',
        hideResolvedCmdMarker(log?.output ?? ''),
      ])
      label = '指令紀錄'
    } else {
      const items = await fetchAllPagedItems(`/admin/history/${encodedUser}`)
      csvHeaders = ['seq', 'username', 'timestamp', 'role', 'content']
      csvRows = items.map((msg, index) => [
        index + 1,
        username,
        msg?.timestamp ?? '',
        msg?.role ?? '',
        hideResolvedCmdMarker(msg?.content ?? ''),
      ])
      label = '對話紀錄'
    }

    const csvText = buildCsvText(csvHeaders, csvRows)
    const fileName = `${sanitizeFilenamePart(username, 'user')}-${activeTab.value}-${buildExportTimestamp()}.csv`
    downloadTextFile(fileName, `\uFEFF${csvText}`, 'text/csv;charset=utf-8')
    exportMessage.value = `已匯出 ${label} CSV（${csvRows.length} 筆）`
  } catch (e) {
    console.error(e)
    exportMessage.value = e?.message || '匯出失敗'
  } finally {
    isExporting.value = false
  }
}

const activeLastUpdatedAt = computed(() => {
  if (activeTab.value === 'audit') return lastAuditUpdatedAt.value
  if (activeTab.value === 'history') return lastHistoryUpdatedAt.value
  return null
})

const lastUpdatedLabel = computed(() => {
  if (!activeLastUpdatedAt.value) return '尚未更新'
  return formatDate(activeLastUpdatedAt.value)
})

const prevAuditPage = async () => {
  if (auditPage.value.page <= 0 || isLoading.value || isAutoRefreshing.value) return
  await fetchAuditLogs(auditPage.value.page - 1)
}

const nextAuditPage = async () => {
  if (!auditPage.value.hasNext || isLoading.value || isAutoRefreshing.value) return
  await fetchAuditLogs(auditPage.value.page + 1)
}

const prevHistoryPage = async () => {
  if (historyPage.value.page <= 0 || isLoading.value || isAutoRefreshing.value) return
  await fetchHistory(historyPage.value.page - 1)
}

const nextHistoryPage = async () => {
  if (!historyPage.value.hasNext || isLoading.value || isAutoRefreshing.value) return
  await fetchHistory(historyPage.value.page + 1)
}

const notifyConversationsUpdated = () => {
  emit('conversations-updated')
}

const purgeSelectedUserChats = async () => {
  if (!selectedUser.value) return
  const ok = window.confirm(`確定要清除使用者「${selectedUser.value}」的「對話紀錄」嗎？此操作無法復原。`)
  if (!ok) return

  isPurging.value = true
  purgeMessage.value = ''
  try {
    const res = await httpClient.delete(`/admin/purge/users/${encodeURIComponent(selectedUser.value)}/chats`)
    const data = res?.data
    const deletedChat = data?.data?.deletedChatMessages ?? 0
    purgeMessage.value = `已清除 ${selectedUser.value}：對話 ${deletedChat} 筆`
    notifyConversationsUpdated()

    const before = selectedUser.value
    await fetchUsers()
    if (!users.value.includes(before)) {
      selectedUser.value = ''
      resetPagedData()
    } else {
      await fetchHistory(DEFAULT_PAGE)
    }
  } catch (e) {
    console.error(e)
    purgeMessage.value = e?.message || '清除失敗'
  } finally {
    isPurging.value = false
  }
}

const purgeSelectedUserCommands = async () => {
  if (!selectedUser.value) return
  const ok = window.confirm(`確定要清除使用者「${selectedUser.value}」的「指令紀錄」嗎？此操作無法復原。`)
  if (!ok) return

  isPurging.value = true
  purgeMessage.value = ''
  try {
    const res = await httpClient.delete(`/admin/purge/users/${encodeURIComponent(selectedUser.value)}/commands`)
    const data = res?.data
    const deletedCmd = data?.data?.deletedCommandLogs ?? 0
    purgeMessage.value = `已清除 ${selectedUser.value}：指令 ${deletedCmd} 筆`

    auditLogs.value = []
    if (activeTab.value === 'audit') {
      await fetchAuditLogs(DEFAULT_PAGE)
    }
  } catch (e) {
    console.error(e)
    purgeMessage.value = e?.message || '清除失敗'
  } finally {
    isPurging.value = false
  }
}

const purgeSelectedUserActivity = async () => {
  if (!selectedUser.value) return
  const ok = window.confirm(`確定要清除使用者「${selectedUser.value}」的「對話 + 指令」紀錄嗎？此操作無法復原。`)
  if (!ok) return

  isPurging.value = true
  purgeMessage.value = ''
  try {
    const user = selectedUser.value
    const res = await httpClient.delete(`/admin/purge/users/${encodeURIComponent(user)}/activity`)
    const data = res?.data
    const deletedChat = data?.data?.deletedChatMessages ?? 0
    const deletedCmd = data?.data?.deletedCommandLogs ?? 0
    purgeMessage.value = `已清除 ${user}：對話 ${deletedChat} 筆、指令 ${deletedCmd} 筆`
    notifyConversationsUpdated()

    await fetchUsers()
    if (!users.value.includes(user)) {
      selectedUser.value = ''
      resetPagedData()
    } else {
      resetPagedData()
      if (activeTab.value === 'audit') await fetchAuditLogs(DEFAULT_PAGE)
      if (activeTab.value === 'history') await fetchHistory(DEFAULT_PAGE)
    }
  } catch (e) {
    console.error(e)
    purgeMessage.value = e?.message || '清除失敗'
  } finally {
    isPurging.value = false
  }
}

const purgeChats = async () => {
  const ok = window.confirm('確定要清除所有「對話紀錄」嗎？此操作無法復原。')
  if (!ok) return

  isPurging.value = true
  purgeMessage.value = ''
  try {
    const res = await httpClient.delete('/admin/purge/chats')
    const data = res?.data
    const deletedChat = data?.data?.deletedChatMessages ?? 0
    purgeMessage.value = `已清除：對話 ${deletedChat} 筆`
    notifyConversationsUpdated()

    selectedUser.value = ''
    resetPagedData()
    await fetchUsers()
  } catch (e) {
    console.error(e)
    purgeMessage.value = e?.message || '清除失敗'
  } finally {
    isPurging.value = false
  }
}

const purgeCommands = async () => {
  const ok = window.confirm('確定要清除所有「指令執行紀錄」嗎？此操作無法復原。')
  if (!ok) return

  isPurging.value = true
  purgeMessage.value = ''
  try {
    const res = await httpClient.delete('/admin/purge/commands')
    const data = res?.data
    const deletedCmd = data?.data?.deletedCommandLogs ?? 0
    purgeMessage.value = `已清除：指令 ${deletedCmd} 筆`

    auditLogs.value = []
    if (activeTab.value === 'audit' && selectedUser.value) {
      await fetchAuditLogs(DEFAULT_PAGE)
    }
  } catch (e) {
    console.error(e)
    purgeMessage.value = e?.message || '清除失敗'
  } finally {
    isPurging.value = false
  }
}
</script>

<template>
  <div class="fixed inset-0 z-50 flex flex-col font-sans" style="background-color: var(--bg-primary); color: var(--text-primary);">
    <!-- Header -->
    <div class="flex items-center justify-between px-6 py-4 border-b" style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
      <div class="flex items-center gap-3">
        <h2 class="text-xl font-bold">Admin Dashboard</h2>
        <span class="text-[10px] px-2 py-0.5 rounded-full font-semibold border"
              style="background-color: color-mix(in srgb, var(--accent-danger) 15%, transparent); color: var(--accent-danger); border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent);">
          Administrator
        </span>
      </div>
      <div class="flex items-center gap-2">
        <!-- Theme toggle -->
        <button @click="themeStore.toggleTheme()"
                class="p-2 rounded-lg border transition-all hover:scale-105"
                style="background-color: var(--bg-tertiary); border-color: var(--border-primary);"
                title="切換主題">
          <svg v-if="themeStore.isDark" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--accent-warning);">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
          </svg>
          <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--accent-primary);">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
          </svg>
        </button>
        <!-- Close button -->
        <button @click="$emit('close')" class="p-2 rounded-lg transition-colors"
                style="color: var(--text-tertiary);"
                @mouseenter="$event.target.style.backgroundColor = 'var(--bg-tertiary)'"
                @mouseleave="$event.target.style.backgroundColor = 'transparent'">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
    </div>

    <div class="flex flex-1 overflow-hidden">
      <!-- Sidebar: User List -->
      <div class="w-60 border-r flex flex-col" style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
        <div class="p-4 border-b" style="border-color: var(--border-primary);">
          <h3 class="text-[10px] font-bold uppercase tracking-widest" style="color: var(--text-tertiary);">Users</h3>
        </div>
        <div class="flex-1 overflow-y-auto p-2 space-y-0.5 custom-scrollbar">
          <button
            v-for="user in users" :key="user"
            @click="selectedUser = user"
            class="w-full text-left px-3 py-2 rounded-lg text-sm transition-all flex items-center gap-2"
            :style="selectedUser === user
              ? { backgroundColor: 'var(--accent-primary)', color: 'white' }
              : { color: 'var(--text-secondary)' }"
            @mouseenter="selectedUser !== user && ($event.currentTarget.style.backgroundColor = 'var(--bg-tertiary)')"
            @mouseleave="selectedUser !== user && ($event.currentTarget.style.backgroundColor = 'transparent')"
          >
            <!-- User avatar circle -->
            <span class="w-6 h-6 rounded-full flex items-center justify-center text-[10px] font-bold flex-shrink-0"
                  :style="selectedUser === user
                    ? { backgroundColor: 'rgba(255,255,255,0.2)', color: 'white' }
                    : { backgroundColor: 'var(--bg-tertiary)', color: 'var(--text-tertiary)' }">
              {{ user.charAt(0).toUpperCase() }}
            </span>
            {{ user }}
          </button>
        </div>
        <!-- Bottom Actions -->
        <div class="p-3 border-t space-y-1.5" style="border-color: var(--border-primary);">
           <button
             @click="purgeChats"
             :disabled="isPurging"
             class="w-full text-left px-3 py-2 rounded-lg text-xs transition-colors border disabled:opacity-50 disabled:cursor-not-allowed"
             style="border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent); color: var(--accent-danger);"
           >
             清除所有對話
           </button>

           <button
             @click="purgeCommands"
             :disabled="isPurging"
             class="w-full text-left px-3 py-2 rounded-lg text-xs transition-colors border disabled:opacity-50 disabled:cursor-not-allowed"
             style="border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent); color: var(--accent-danger);"
           >
             清除所有指令
           </button>

           <div v-if="purgeMessage" class="text-[10px] leading-snug px-1" style="color: var(--text-tertiary);">
             {{ purgeMessage }}
           </div>
        </div>
      </div>

      <!-- Main Content -->
      <div class="flex-1 flex flex-col overflow-hidden relative" style="background-color: var(--bg-primary);">
        <!-- Tabs -->
        <div class="flex border-b sticky top-0 z-10" style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
          <button @click="activeTab = 'audit'"
                  class="px-6 py-3 text-sm font-medium border-b-2 transition-colors"
                  :style="activeTab === 'audit'
                    ? { borderColor: 'var(--accent-primary)', color: 'var(--accent-primary)' }
                    : { borderColor: 'transparent', color: 'var(--text-tertiary)' }">
            指令審計
          </button>
          <button @click="activeTab = 'history'"
                  class="px-6 py-3 text-sm font-medium border-b-2 transition-colors"
                  :style="activeTab === 'history'
                    ? { borderColor: 'var(--accent-primary)', color: 'var(--accent-primary)' }
                    : { borderColor: 'transparent', color: 'var(--text-tertiary)' }">
            對話紀錄
          </button>
          <button @click="activeTab = 'models'"
                  class="px-6 py-3 text-sm font-medium border-b-2 transition-colors ml-auto"
                  :style="activeTab === 'models'
                    ? { borderColor: 'var(--accent-primary)', color: 'var(--accent-primary)' }
                    : { borderColor: 'transparent', color: 'var(--text-tertiary)' }">
            模型設定
          </button>
        </div>

        <!-- Models Management View -->
        <div v-if="activeTab === 'models'" class="flex-1 overflow-y-auto p-8 custom-scrollbar">
          <div class="flex items-baseline justify-between gap-4 mb-6">
            <h3 class="text-2xl font-bold">AI 模型配置</h3>
            <div class="text-xs" style="color: var(--text-tertiary);">提示：修改會寫入 DB；重啟不會被 application.properties 覆蓋</div>
          </div>

          <!-- Add new model -->
          <div class="mb-6 p-5 rounded-xl border" style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
            <div class="flex items-center justify-between gap-4 mb-4">
              <div class="font-semibold text-sm">新增模型</div>
              <button
                @click="addModel"
                :disabled="isModelSaving"
                class="px-3 py-1.5 rounded-lg text-xs font-medium text-white disabled:opacity-50 disabled:cursor-not-allowed"
                style="background-color: var(--accent-primary);"
              >
                新增
              </button>
            </div>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-3">
              <label class="text-xs" style="color: var(--text-tertiary);">
                id
                <input v-model="newModel.id" class="admin-input mt-1" placeholder="e.g. 70b" />
              </label>
              <label class="text-xs" style="color: var(--text-tertiary);">
                label
                <input v-model="newModel.label" class="admin-input mt-1" placeholder="顯示名稱" />
              </label>
              <label class="text-xs md:col-span-2" style="color: var(--text-tertiary);">
                name (provider model name)
                <input v-model="newModel.name" class="admin-input mt-1 font-mono" placeholder="e.g. llama-3.3-70b-versatile" />
              </label>
              <label class="text-xs" style="color: var(--text-tertiary);">
                tpm
                <input v-model.number="newModel.tpm" type="number" min="0" class="admin-input mt-1 font-mono" />
              </label>
              <label class="text-xs" style="color: var(--text-tertiary);">
                category
                <input v-model="newModel.category" class="admin-input mt-1" placeholder="Other" />
              </label>
              <label class="text-xs flex items-center gap-2" style="color: var(--text-tertiary);">
                <input v-model="newModel.enabled" type="checkbox" class="accent-[var(--accent-primary)]" />
                enabled
              </label>
            </div>
            <div v-if="modelMessage" class="mt-3 text-[11px]" style="color: var(--text-tertiary);">{{ modelMessage }}</div>
          </div>

          <!-- Model list -->
          <div class="grid gap-3">
            <div v-for="m in models" :key="m.id" class="p-5 rounded-xl border" style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
              <div class="flex items-start justify-between gap-4">
                <div class="min-w-0">
                  <div class="text-[10px]" style="color: var(--text-tertiary);">id</div>
                  <div class="font-mono text-sm truncate" style="color: var(--text-primary);">{{ m.id }}</div>
                </div>
                <div class="flex items-center gap-2 flex-shrink-0">
                  <label class="text-xs flex items-center gap-1.5" style="color: var(--text-tertiary);">
                    <input v-model="m.enabled" type="checkbox" class="accent-[var(--accent-primary)]" />
                    enabled
                  </label>
                  <button
                    @click="saveModel(m)"
                    :disabled="isModelSaving"
                    class="px-3 py-1.5 rounded-lg text-xs font-medium text-white disabled:opacity-50 disabled:cursor-not-allowed"
                    style="background-color: var(--accent-primary);"
                  >
                    儲存
                  </button>
                  <button
                    @click="deleteModel(m.id)"
                    :disabled="isModelSaving"
                    class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                    style="border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent); color: var(--accent-danger);"
                  >
                    刪除
                  </button>
                </div>
              </div>

              <div class="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3">
                <label class="text-xs" style="color: var(--text-tertiary);">
                  label
                  <input v-model="m.label" class="admin-input mt-1" />
                </label>
                <label class="text-xs" style="color: var(--text-tertiary);">
                  category
                  <input v-model="m.category" class="admin-input mt-1" />
                </label>
                <label class="text-xs md:col-span-2" style="color: var(--text-tertiary);">
                  name
                  <input v-model="m.name" class="admin-input mt-1 font-mono" />
                </label>
                <label class="text-xs" style="color: var(--text-tertiary);">
                  tpm
                  <input v-model.number="m.tpm" type="number" min="0" class="admin-input mt-1 font-mono" />
                </label>
              </div>
            </div>
          </div>
        </div>

        <!-- User Data View -->
        <div v-else class="flex-1 overflow-y-auto p-6 custom-scrollbar">
            <!-- Selected user actions -->
            <div v-if="selectedUser" class="mb-4 flex flex-wrap items-center gap-2">
              <span class="text-xs" style="color: var(--text-tertiary);">Selected:</span>
              <span class="text-xs font-mono px-2 py-1 rounded-lg border" style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-primary);">{{ selectedUser }}</span>
              <span class="text-[10px]" style="color: var(--text-tertiary);">
                最後更新：{{ lastUpdatedLabel }}（每 30 秒自動更新）
              </span>

              <button
                @click="refreshCurrentView"
                :disabled="isPurging || isLoading || isManualRefreshing || isAutoRefreshing || isExporting"
                class="ml-auto px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-secondary);"
              >
                {{ (isManualRefreshing || isAutoRefreshing) ? '更新中...' : '重新整理' }}
              </button>

              <button
                @click="exportSelectedUserCsv"
                :disabled="isPurging || isLoading || isExporting || !isExportTab"
                class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                style="border-color: color-mix(in srgb, var(--accent-primary) 35%, transparent); color: var(--accent-primary);"
              >
                {{ isExporting ? '匯出中...' : '匯出 CSV' }}
              </button>

              <button
                @click="purgeSelectedUserChats"
                :disabled="isPurging || isExporting"
                class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                style="border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent); color: var(--accent-danger);"
              >
                清除對話
              </button>
              <button
                @click="purgeSelectedUserCommands"
                :disabled="isPurging || isExporting"
                class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                style="border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent); color: var(--accent-danger);"
              >
                清除指令
              </button>
              <button
                @click="purgeSelectedUserActivity"
                :disabled="isPurging || isExporting"
                class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                style="border-color: color-mix(in srgb, var(--accent-danger) 30%, transparent); color: var(--accent-danger);"
              >
                清除全部
              </button>
            </div>
            <div v-if="exportMessage" class="-mt-2 mb-4 text-[11px]" style="color: var(--text-tertiary);">
              {{ exportMessage }}
            </div>

            <!-- Empty state -->
            <div v-if="!selectedUser" class="h-full flex flex-col items-center justify-center opacity-40">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-16 w-16 mb-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
              </svg>
              <p style="color: var(--text-tertiary);">請從左側選擇一位使用者</p>
            </div>

            <!-- Loading -->
            <div v-else-if="isLoading" class="flex justify-center mt-20">
              <div class="animate-spin rounded-full h-8 w-8 border-b-2" style="border-color: var(--accent-primary);"></div>
            </div>

            <!-- Audit Logs Table -->
            <div v-else-if="activeTab === 'audit'" class="space-y-4">
              <div v-if="auditLogs.length === 0" class="text-center py-10" style="color: var(--text-tertiary);">尚無指令執行紀錄</div>
              <div v-else class="overflow-hidden rounded-xl border" style="background-color: var(--bg-secondary); border-color: var(--border-primary);">
                <table class="w-full text-left border-collapse">
                  <thead>
                    <tr style="background-color: var(--bg-tertiary);">
                      <th class="py-3 px-4 border-b text-xs uppercase font-semibold" style="border-color: var(--border-primary); color: var(--text-tertiary);">時間</th>
                      <th class="py-3 px-4 border-b text-xs uppercase font-semibold" style="border-color: var(--border-primary); color: var(--text-tertiary);">狀態</th>
                      <th class="py-3 px-4 border-b text-xs uppercase font-semibold" style="border-color: var(--border-primary); color: var(--text-tertiary);">類型</th>
                      <th class="py-3 px-4 border-b text-xs uppercase font-semibold" style="border-color: var(--border-primary); color: var(--text-tertiary);">指令</th>
                      <th class="py-3 px-4 border-b text-xs uppercase font-semibold" style="border-color: var(--border-primary); color: var(--text-tertiary);">輸出結果</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="log in sortedAuditLogs" :key="log.id" class="transition-colors border-b" style="border-color: var(--border-primary);"
                        @mouseenter="$event.currentTarget.style.backgroundColor = 'var(--bg-tertiary)'"
                        @mouseleave="$event.currentTarget.style.backgroundColor = 'transparent'">
                      <td class="py-3 px-4 text-xs whitespace-nowrap font-mono" style="color: var(--text-tertiary);">{{ formatDate(log.executionTime) }}</td>
                      <td class="py-3 px-4">
                        <span class="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold"
                              :style="log.success
                                ? { backgroundColor: 'color-mix(in srgb, var(--accent-success) 15%, transparent)', color: 'var(--accent-success)' }
                                : { backgroundColor: 'color-mix(in srgb, var(--accent-danger) 15%, transparent)', color: 'var(--accent-danger)' }">
                          {{ log.success ? 'SUCCESS' : 'FAILED' }}
                        </span>
                        <div v-if="!log.success && log.exitCode !== null && log.exitCode !== undefined"
                             class="mt-1 text-[10px] font-mono"
                             style="color: var(--text-tertiary);">
                          exit: {{ log.exitCode }}
                        </div>
                      </td>
                      <td class="py-3 px-4">
                        <span v-if="log.commandType === 'MODIFY'"
                              class="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold"
                              style="background-color: color-mix(in srgb, var(--accent-warning) 15%, transparent); color: var(--accent-warning);">
                          MODIFY
                        </span>
                        <span v-else
                              class="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold"
                              style="background-color: color-mix(in srgb, var(--accent-primary) 10%, transparent); color: var(--text-tertiary);">
                          READ
                        </span>
                      </td>
                      <td class="py-3 px-4">
                        <code class="text-xs font-mono px-2 py-1 rounded border" style="background-color: var(--code-bg); border-color: var(--border-primary); color: var(--code-text);">{{ log.command }}</code>
                      </td>
                      <td class="py-3 px-4">
                        <details class="group">
                          <summary class="cursor-pointer text-xs select-none flex items-center gap-1" style="color: var(--text-tertiary);">
                            <span class="group-open:hidden">&#9654; 顯示輸出</span>
                            <span class="hidden group-open:inline">&#9660; 隱藏輸出</span>
                          </summary>
                          <div class="mt-2">
                            <pre class="text-[10px] leading-relaxed font-mono p-3 rounded-lg border overflow-x-auto max-h-60 custom-scrollbar"
                                 style="background-color: var(--code-bg); border-color: var(--border-primary); color: var(--text-secondary);">{{ hideResolvedCmdMarker(log.output) }}</pre>
                          </div>
                        </details>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="auditPage.totalElements > 0" class="flex items-center justify-between gap-3 px-1">
                <span class="text-xs" style="color: var(--text-tertiary);">
                  第 {{ auditPage.page + 1 }} / {{ Math.max(auditPage.totalPages, 1) }} 頁，共 {{ auditPage.totalElements }} 筆
                </span>
                <div class="flex items-center gap-2">
                  <button
                    @click="prevAuditPage"
                    :disabled="isLoading || isAutoRefreshing || auditPage.page <= 0"
                    class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                    style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-secondary);"
                  >
                    上一頁
                  </button>
                  <button
                    @click="nextAuditPage"
                    :disabled="isLoading || isAutoRefreshing || !auditPage.hasNext"
                    class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                    style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-secondary);"
                  >
                    下一頁
                  </button>
                </div>
              </div>
            </div>

            <!-- Chat History List -->
            <div v-else-if="activeTab === 'history'" class="space-y-4 max-w-4xl mx-auto">
              <div v-if="chatHistory.length === 0" class="text-center py-10" style="color: var(--text-tertiary);">尚無對話紀錄</div>
              <div v-for="(msg, idx) in chatHistory" :key="idx" class="flex gap-3">
                <div class="flex-shrink-0 w-7 h-7 rounded-lg flex items-center justify-center text-[10px] font-bold"
                     :class="msg.role === 'user' ? 'bg-gradient-to-br from-indigo-500 to-purple-600 text-white' : 'bg-gradient-to-br from-emerald-500 to-teal-600 text-white'">
                  {{ msg.role === 'user' ? 'U' : 'AI' }}
                </div>
                <div class="flex-1 min-w-0">
                  <div class="flex items-baseline gap-2 mb-1">
                    <span class="text-sm font-semibold" style="color: var(--text-primary);">{{ msg.role === 'user' ? 'User' : 'Assistant' }}</span>
                    <span class="text-[10px]" style="color: var(--text-tertiary);">{{ formatDate(msg.timestamp) }}</span>
                  </div>
                  <div class="markdown-content rounded-xl p-4 border text-sm leading-relaxed shadow-sm"
                       style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-secondary);"
                       v-html="renderMarkdown(msg.content)">
                  </div>
                </div>
              </div>
              <div v-if="historyPage.totalElements > 0" class="flex items-center justify-between gap-3 px-1">
                <span class="text-xs" style="color: var(--text-tertiary);">
                  第 {{ historyPage.page + 1 }} / {{ Math.max(historyPage.totalPages, 1) }} 頁，共 {{ historyPage.totalElements }} 筆
                </span>
                <div class="flex items-center gap-2">
                  <button
                    @click="prevHistoryPage"
                    :disabled="isLoading || isAutoRefreshing || historyPage.page <= 0"
                    class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                    style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-secondary);"
                  >
                    上一頁
                  </button>
                  <button
                    @click="nextHistoryPage"
                    :disabled="isLoading || isAutoRefreshing || !historyPage.hasNext"
                    class="px-3 py-1.5 rounded-lg text-xs border disabled:opacity-50 disabled:cursor-not-allowed"
                    style="background-color: var(--bg-secondary); border-color: var(--border-primary); color: var(--text-secondary);"
                  >
                    下一頁
                  </button>
                </div>
              </div>
            </div>

          </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.admin-input {
  width: 100%;
  background-color: var(--bg-input);
  border: 1px solid var(--border-primary);
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
  font-size: 0.875rem;
  color: var(--text-primary);
  outline: none;
  transition: border-color 0.2s;
}
.admin-input:focus {
  border-color: var(--accent-primary);
}
</style>
