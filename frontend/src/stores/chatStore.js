import { defineStore } from 'pinia'
import { ref, computed, watch } from 'vue'
import { chatApi } from '../api/chatApi.js'
import { useSystemStore } from './systemStore.js'
import { CHAT_CONFIG } from '../config/app.config.js'
import { DEFAULTS } from '../constants/index.js'
import { hydrateMessageWithCommand } from '../utils/commandMarkers.js'

const MODEL_PREFERENCE_STORAGE_KEY = 'server-assistant:model-preference'
const DRAFT_STORAGE_KEY_PREFIX = 'draft_'

function readSavedModelPreference() {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return ''
  }

  try {
    const saved = window.localStorage.getItem(MODEL_PREFERENCE_STORAGE_KEY)
    return typeof saved === 'string' ? saved.trim() : ''
  } catch {
    return ''
  }
}

function persistModelPreference(modelKey) {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return
  }

  try {
    const normalizedModel = typeof modelKey === 'string' ? modelKey.trim() : ''
    if (normalizedModel) {
      window.localStorage.setItem(MODEL_PREFERENCE_STORAGE_KEY, normalizedModel)
      return
    }
    window.localStorage.removeItem(MODEL_PREFERENCE_STORAGE_KEY)
  } catch {
    // Ignore localStorage failures (e.g., privacy mode restrictions).
  }
}

function readSavedDraft(conversationKey) {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return ''
  }

  try {
    const saved = window.localStorage.getItem(getDraftStorageKey(conversationKey))
    return typeof saved === 'string' ? saved : ''
  } catch {
    return ''
  }
}

function persistDraft(conversationKey, value) {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return
  }

  try {
    if (value) {
      window.localStorage.setItem(getDraftStorageKey(conversationKey), value)
      return
    }
    window.localStorage.removeItem(getDraftStorageKey(conversationKey))
  } catch {
    // Ignore localStorage failures (e.g., privacy mode restrictions).
  }
}

function clearPersistedDrafts() {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') {
    return
  }

  try {
    const keysToRemove = []
    for (let index = 0; index < window.localStorage.length; index += 1) {
      const storageKey = window.localStorage.key(index)
      if (typeof storageKey === 'string' && storageKey.startsWith(DRAFT_STORAGE_KEY_PREFIX)) {
        keysToRemove.push(storageKey)
      }
    }

    for (const storageKey of keysToRemove) {
      window.localStorage.removeItem(storageKey)
    }
  } catch {
    // Ignore localStorage failures (e.g., privacy mode restrictions).
  }
}

function getDraftStorageKey(conversationKey) {
  return `${DRAFT_STORAGE_KEY_PREFIX}${conversationKey}`
}

/**
 * Chat Store
 *
 * Manages chat state (messages, input, model selection).
 * Streaming logic lives in useChat.js composable.
 */
export const useChatStore = defineStore('chat', () => {
  const DEFAULT_HISTORY_PAGE_SIZE = 50
  const HISTORY_PAGE_SIZE = Number.isInteger(CHAT_CONFIG.historyPageSize) &&
    CHAT_CONFIG.historyPageSize > 0
    ? CHAT_CONFIG.historyPageSize
    : DEFAULT_HISTORY_PAGE_SIZE
  const NEW_CONVERSATION_DRAFT_KEY = '__new_conversation__'

  // ========== State ==========
  const messages = ref([])
  const draftByConversationId = ref({})
  const activeDraftConversationId = ref(NEW_CONVERSATION_DRAFT_KEY)
  const userInput = computed({
    get() {
      return draftByConversationId.value[activeDraftConversationId.value] ?? ''
    },
    set(value) {
      updateDraftForKey(activeDraftConversationId.value, value)
    },
  })
  const isProcessing = ref(false)
  const model = ref(readSavedModelPreference() || DEFAULTS.MODEL)
  const hasMoreHistory = ref(false)
  const isHistoryLoading = ref(false)
  const isLoadingMore = ref(false)
  const totalHistory = ref(0)
  const loadedHistoryCount = ref(0)
  const historyCursorCreatedAt = ref(null)
  const historyCursorId = ref(null)
  const pendingHistoryReloadConversationId = ref('')
  const historyLoadFailed = ref(false)

  // ========== Computed ==========
  const displayModelName = computed(() => {
    const systemStore = useSystemStore()
    if (systemStore.availableModels[model.value]) {
      return systemStore.availableModels[model.value].label
    }
    return model.value
  })
  const hasPendingHistoryReload = computed(() => pendingHistoryReloadConversationId.value.length > 0)

  // ========== Actions ==========

  function clearMessages() {
    messages.value = []
    hasMoreHistory.value = false
    isHistoryLoading.value = false
    isLoadingMore.value = false
    historyLoadFailed.value = false
    totalHistory.value = 0
    loadedHistoryCount.value = 0
    historyCursorCreatedAt.value = null
    historyCursorId.value = null
    pendingHistoryReloadConversationId.value = ''
  }

  /**
   * Load the most recent page of chat history for a conversation.
   * Resets pagination state so "load more" starts from the correct offset.
   * @param {string} conversationId - Conversation ID
   */
  async function loadHistory(conversationId) {
    if (!conversationId) return false

    isHistoryLoading.value = true
    historyLoadFailed.value = false
    messages.value = []
    hasMoreHistory.value = false
    totalHistory.value = 0
    loadedHistoryCount.value = 0
    historyCursorCreatedAt.value = null
    historyCursorId.value = null
    pendingHistoryReloadConversationId.value = ''

    try {
      const result = await chatApi.getHistory(conversationId, { limit: HISTORY_PAGE_SIZE })

      if (result.success) {
        const { messages: msgs, total, nextCursorCreatedAt, nextCursorId } = result.data ?? {}
        const history = Array.isArray(msgs) ? msgs : []
        messages.value = history.map(hydrateMessageWithCommand)
        loadedHistoryCount.value = history.length
        totalHistory.value = typeof total === 'number' ? total : 0
        historyCursorCreatedAt.value = normalizeCursorCreatedAt(nextCursorCreatedAt)
        historyCursorId.value = normalizeCursorId(nextCursorId)
        hasMoreHistory.value = hasRemainingHistory()
        return true
      }
      historyLoadFailed.value = true
      return false
    } catch (error) {
      console.error('Load history error:', error)
      historyLoadFailed.value = true
      if (shouldRetryHistoryWhenOnline(error)) {
        pendingHistoryReloadConversationId.value = conversationId
      }
      return false
    } finally {
      isHistoryLoading.value = false
    }
  }

  /**
   * Load the next older page of messages and prepend them to the current list.
   * @param {string} conversationId - Conversation ID
   */
  async function loadMoreHistory(conversationId) {
    if (!conversationId || !hasMoreHistory.value || isLoadingMore.value) return

    if (!hasValidCursor()) {
      hasMoreHistory.value = false
      return
    }

    isLoadingMore.value = true
    try {
      const result = await chatApi.getHistory(conversationId, {
        limit: HISTORY_PAGE_SIZE,
        beforeCreatedAt: historyCursorCreatedAt.value,
        beforeId: historyCursorId.value,
      })

      if (result.success) {
        const { messages: msgs, total, nextCursorCreatedAt, nextCursorId } = result.data ?? {}
        const older = Array.isArray(msgs) ? msgs.map(hydrateMessageWithCommand) : []
        messages.value = [...older, ...messages.value]
        loadedHistoryCount.value += older.length
        totalHistory.value = typeof total === 'number' ? total : totalHistory.value
        historyCursorCreatedAt.value = normalizeCursorCreatedAt(nextCursorCreatedAt)
        historyCursorId.value = normalizeCursorId(nextCursorId)
        hasMoreHistory.value = hasRemainingHistory()
      }
    } catch (error) {
      console.error('Load more history error:', error)
    } finally {
      isLoadingMore.value = false
    }
  }

  async function retryPendingHistoryReload() {
    if (!pendingHistoryReloadConversationId.value) return false
    if (isHistoryLoading.value) return false
    return loadHistory(pendingHistoryReloadConversationId.value)
  }

  /**
   * Clear conversation history on server
   * @param {string} conversationId - Conversation ID
   */
  async function clearHistory(conversationId) {
    if (!conversationId) return

    try {
      const result = await chatApi.clearHistory(conversationId)

      if (result.success) {
        clearMessages()
        return true
      }
      return false
    } catch (error) {
      console.error('Clear history error:', error)
      return false
    }
  }

  /**
   * Set AI model
   * @param {string} modelKey - Model key (e.g., '20b', '120b')
   */
  function setModel(modelKey) {
    model.value = modelKey
  }

  /**
   * Set current user input draft.
   * @param {string} value - Input text
   */
  function setUserInput(value) {
    userInput.value = value
  }

  /**
   * Select which conversation's draft is currently bound to userInput.
   * @param {string} conversationId - Conversation ID
   */
  function setActiveDraftConversation(conversationId) {
    const conversationKey = normalizeDraftConversationKey(conversationId)
    activeDraftConversationId.value = conversationKey
    hydrateDraftForKey(conversationKey)
  }

  /**
   * Get draft text for a specific conversation.
   * @param {string} conversationId - Conversation ID
   * @returns {string}
   */
  function getConversationDraft(conversationId) {
    const key = normalizeDraftConversationKey(conversationId)
    hydrateDraftForKey(key)
    return draftByConversationId.value[key] ?? ''
  }

  /**
   * Set draft text for a specific conversation.
   * @param {string} conversationId - Conversation ID
   * @param {string} value - Draft text
   */
  function setConversationDraft(conversationId, value) {
    const key = normalizeDraftConversationKey(conversationId)
    updateDraftForKey(key, value)
  }

  /**
   * Clear draft text for a specific conversation.
   * @param {string} conversationId - Conversation ID
   */
  function clearConversationDraft(conversationId) {
    setConversationDraft(conversationId, '')
  }

  /**
   * Move draft text from one conversation key to another.
   * Useful when an unsaved conversation is assigned a backend ID.
   * @param {string} sourceConversationId - Source conversation ID
   * @param {string} targetConversationId - Target conversation ID
   */
  function moveConversationDraft(sourceConversationId, targetConversationId) {
    const sourceKey = normalizeDraftConversationKey(sourceConversationId)
    const targetKey = normalizeDraftConversationKey(targetConversationId)
    if (sourceKey === targetKey) return

    hydrateDraftForKey(sourceKey)
    const sourceDraft = draftByConversationId.value[sourceKey]
    if (typeof sourceDraft !== 'string') return

    const nextDrafts = { ...draftByConversationId.value }
    if (sourceDraft.length > 0) {
      nextDrafts[targetKey] = sourceDraft
      persistDraft(targetKey, sourceDraft)
    } else {
      delete nextDrafts[targetKey]
      persistDraft(targetKey, '')
    }
    delete nextDrafts[sourceKey]
    persistDraft(sourceKey, '')
    draftByConversationId.value = nextDrafts
  }

  function clearAllDrafts() {
    draftByConversationId.value = {}
    activeDraftConversationId.value = NEW_CONVERSATION_DRAFT_KEY
    clearPersistedDrafts()
  }

  function hasValidCursor() {
    return historyCursorCreatedAt.value !== null && historyCursorId.value !== null
  }

  function hasRemainingHistory() {
    if (loadedHistoryCount.value >= totalHistory.value) {
      return false
    }
    return hasValidCursor()
  }

  function shouldRetryHistoryWhenOnline(error) {
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      return true
    }

    if (!error || typeof error !== 'object') return false
    if (error.code === 'ERR_NETWORK') return true
    if (error.request && !error.response) return true

    const normalizedMessage = typeof error.message === 'string'
      ? error.message.toLowerCase()
      : ''
    return normalizedMessage.includes('network error') ||
      normalizedMessage.includes('failed to fetch')
  }

  function normalizeCursorCreatedAt(value) {
    if (typeof value !== 'string') return null
    const trimmedValue = value.trim()
    return trimmedValue.length > 0 ? trimmedValue : null
  }

  function normalizeCursorId(value) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value
    }
    return null
  }

  function normalizeDraftConversationKey(conversationId) {
    if (typeof conversationId !== 'string') return NEW_CONVERSATION_DRAFT_KEY
    const trimmedConversationId = conversationId.trim()
    return trimmedConversationId || NEW_CONVERSATION_DRAFT_KEY
  }

  function updateDraftForKey(conversationKey, value) {
    const normalizedValue = typeof value === 'string' ? value : ''
    const currentValue = draftByConversationId.value[conversationKey]

    if (!normalizedValue) {
      persistDraft(conversationKey, '')
      if (typeof currentValue !== 'string') return
      const nextDrafts = { ...draftByConversationId.value }
      delete nextDrafts[conversationKey]
      draftByConversationId.value = nextDrafts
      return
    }

    if (currentValue === normalizedValue) return
    draftByConversationId.value = {
      ...draftByConversationId.value,
      [conversationKey]: normalizedValue,
    }
    persistDraft(conversationKey, normalizedValue)
  }

  function hydrateDraftForKey(conversationKey) {
    if (typeof draftByConversationId.value[conversationKey] === 'string') return
    const savedDraft = readSavedDraft(conversationKey)
    if (!savedDraft) return
    draftByConversationId.value = {
      ...draftByConversationId.value,
      [conversationKey]: savedDraft,
    }
  }

  watch(model, (nextModel) => {
    persistModelPreference(nextModel)
  })

  // ========== Return Public API ==========
  return {
    // State
    messages,
    userInput,
    draftByConversationId,
    isProcessing,
    model,
    hasMoreHistory,
    isHistoryLoading,
    isLoadingMore,
    historyLoadFailed,
    totalHistory,
    pendingHistoryReloadConversationId,
    hasPendingHistoryReload,
    // Computed
    displayModelName,
    // Actions
    clearMessages,
    loadHistory,
    loadMoreHistory,
    retryPendingHistoryReload,
    clearHistory,
    setModel,
    setUserInput,
    setActiveDraftConversation,
    getConversationDraft,
    setConversationDraft,
    clearConversationDraft,
    moveConversationDraft,
    clearAllDrafts,
  }
})
