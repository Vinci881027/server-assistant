import { ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useChatStore } from '../stores/chatStore'
import { useConversationStore } from '../stores/conversationStore'
import { useSystemStore } from '../stores/systemStore'
import { chatApi } from '../api'
import { extractCommandMarker, extractRateLimitMarker, hasPendingCommandMarker } from '../utils/commandMarkers'
import { CHAT_CONFIG } from '../config/app.config'

// ========== Error Classification ==========

const MAX_RETRIES = 2
const RETRY_BASE_DELAY_MS = 1500
const RETRY_JITTER_MAX_MS = 3000

function extractErrorCode(payload) {
  if (!payload || typeof payload !== 'object') return null
  if (typeof payload.errorCode === 'string' && payload.errorCode.trim()) {
    return payload.errorCode
  }
  if (payload.error && typeof payload.error === 'object' && typeof payload.error.code === 'string' && payload.error.code.trim()) {
    return payload.error.code
  }
  return null
}

function parseRateLimitReason(value) {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (trimmed === 'user_rate_limit' || trimmed === 'user_tpm_limit' || trimmed === 'global_tpm_limit') {
    return trimmed
  }
  return null
}

function extractRateLimitReason(payload) {
  if (!payload || typeof payload !== 'object') return null
  return parseRateLimitReason(payload.reason)
    ?? parseRateLimitReason(payload.data?.reason)
    ?? parseRateLimitReason(payload.details?.reason)
    ?? parseRateLimitReason(payload.data?.details?.reason)
    ?? parseRateLimitReason(payload.error?.reason)
    ?? parseRateLimitReason(payload.error?.details?.reason)
}

function readRetryAfterSecFromPayload(payload) {
  if (!payload || typeof payload !== 'object') return null
  return parsePositiveInt(payload.retryAfterSeconds)
    ?? parsePositiveInt(payload.details?.retryAfterSeconds)
    ?? parsePositiveInt(payload.data?.retryAfterSeconds)
    ?? parsePositiveInt(payload.data?.details?.retryAfterSeconds)
    ?? parsePositiveInt(payload.error?.retryAfterSeconds)
    ?? parsePositiveInt(payload.error?.details?.retryAfterSeconds)
}

async function readErrorMetadataFromResponse(response) {
  try {
    const rawBody = await response.text()
    if (!rawBody) return { errorCode: null, rateLimitReason: null, retryAfterSec: null }
    const parsed = JSON.parse(rawBody)
    return {
      errorCode: extractErrorCode(parsed),
      rateLimitReason: extractRateLimitReason(parsed),
      retryAfterSec: readRetryAfterSecFromPayload(parsed),
    }
  } catch {
    return { errorCode: null, rateLimitReason: null, retryAfterSec: null }
  }
}

function parsePositiveInt(value) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null
}

function parseNonNegativeInt(value) {
  const parsed = Number.parseInt(String(value ?? ''), 10)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : null
}

function readRetryAfterSecFromHeaders(headers) {
  if (!headers) return null

  // Native Fetch Headers
  if (typeof headers.get === 'function') {
    return parsePositiveInt(headers.get('retry-after'))
  }

  // Plain object headers (e.g., Axios-style lower-case keys)
  if (typeof headers === 'object') {
    const direct = headers['retry-after'] ?? headers['Retry-After']
    if (direct != null) return parsePositiveInt(direct)

    const matchedKey = Object.keys(headers).find((key) => key.toLowerCase() === 'retry-after')
    if (matchedKey) return parsePositiveInt(headers[matchedKey])
  }

  return null
}

function resolveHttpStatus(error) {
  if (!error || typeof error !== 'object') return null
  return parsePositiveInt(error.httpStatus)
    ?? parsePositiveInt(error.status)
    ?? parsePositiveInt(error.response?.status)
}

function hydrateRateLimitMetadata(error, httpStatus) {
  if (!error || typeof error !== 'object' || httpStatus !== 429) return
  const payload = error.response?.data ?? error.data

  if (!error.errorCode) {
    const errorCode = extractErrorCode(payload)
    if (errorCode) error.errorCode = errorCode
  }
  if (!error.rateLimitReason) {
    const rateLimitReason = extractRateLimitReason(payload)
    if (rateLimitReason) error.rateLimitReason = rateLimitReason
  }
  if (parsePositiveInt(error.retryAfterSec) != null) return

  const retryAfterSecFromBody = readRetryAfterSecFromPayload(payload)

  const retryAfterSec = readRetryAfterSecFromHeaders(error.headers)
    ?? readRetryAfterSecFromHeaders(error.response?.headers)
    ?? retryAfterSecFromBody

  if (retryAfterSec != null) {
    error.retryAfterSec = retryAfterSec
  }
}

/**
 * Classify a fetch/stream error into a typed structure.
 * @param {Error} error
 * @param {number|null} httpStatus
 * @returns {{ type: string, retryable: boolean, delayMs: number, allKeysExhausted: boolean }}
 */
function classifyError(error, httpStatus) {
  if (error instanceof TypeError) {
    // TypeError: Failed to fetch → network disconnection
    return { type: 'network', retryable: true, delayMs: RETRY_BASE_DELAY_MS, allKeysExhausted: false }
  }
  switch (httpStatus) {
    case 401:
    case 403:
      return { type: 'auth', retryable: false, delayMs: 0, allKeysExhausted: false }
    case 429: {
      const remainingKeyCount = parseNonNegativeInt(error?.remainingKeyCount)
      const rateLimitReason = parseRateLimitReason(error?.rateLimitReason)
      if (error?.errorCode === 'CONCURRENT_STREAM_LIMIT_EXCEEDED') {
        return { type: 'concurrentStream', retryable: false, delayMs: 0, allKeysExhausted: false }
      }
      const retryAfterSec = parsePositiveInt(error?.retryAfterSec) ?? 10
      const jitterMs = Math.random() * RETRY_JITTER_MAX_MS
      const retryAfterMs = retryAfterSec * 1000 + jitterMs
      return {
        type: 'rateLimit',
        retryable: true,
        delayMs: retryAfterMs,
        allKeysExhausted: remainingKeyCount === 0,
        rateLimitReason,
      }
    }
    case 502:
    case 503:
    case 504:
      return { type: 'serverUnavailable', retryable: true, delayMs: RETRY_BASE_DELAY_MS * 2, allKeysExhausted: false }
    default:
      if (httpStatus != null && httpStatus >= 500) {
        return { type: 'serverError', retryable: false, delayMs: 0, allKeysExhausted: false }
      }
      return { type: 'unknown', retryable: false, delayMs: 0, allKeysExhausted: false }
  }
}

function buildErrorMessage(type, attempt, delaySec = 0, options = {}) {
  const retryNote = attempt > 0 ? `（第 ${attempt} 次重試）` : ''
  switch (type) {
    case 'network':      return `❌ 網路連線中斷，請確認連線狀態。${retryNote}`
    case 'auth':         return '❌ 驗證失敗，請重新登入。'
    case 'concurrentStream': return '⚠️ 您已有對話進行中，請等待其完成後再發送。'
    case 'rateLimit':
      if (options.rateLimitReason === 'user_rate_limit' || options.rateLimitReason === 'user_tpm_limit') {
        return delaySec > 0
          ? `⏳ 您的請求太頻繁，請稍後 ${delaySec} 秒。${retryNote}`
          : `⏳ 您的請求太頻繁，請稍後再試。${retryNote}`
      }
      if (options.rateLimitReason === 'global_tpm_limit') {
        return delaySec > 0
          ? `⏳ 系統目前繁忙，請稍後 ${delaySec} 秒再試。${retryNote}`
          : `⏳ 系統目前繁忙，請稍後再試。${retryNote}`
      }
      if (options.allKeysExhausted) {
        return delaySec > 0
          ? `⏳ 目前 AI 服務繁忙，請等待 ${delaySec} 秒後重試，或切換至其他模型。${retryNote}`
          : `⏳ 目前 AI 服務繁忙，請稍後重試，或切換至其他模型。${retryNote}`
      }
      return delaySec > 0
        ? `⏳ 請等待 ${delaySec} 秒後重試。${retryNote}`
        : `⏳ 已達 AI 速率上限，請稍後再試。${retryNote}`
    case 'serverUnavailable': return `❌ 伺服器暫時無法回應，請稍後再試。${retryNote}`
    case 'serverError':  return '❌ 伺服器發生內部錯誤，請稍後再試。'
    default:             return '❌ 伺服器回應異常，請稍後再試。'
  }
}

function buildRetryStatusMessage(classification, remainingSec, attempt) {
  if (classification.type === 'rateLimit') {
    if (classification.rateLimitReason === 'user_rate_limit' || classification.rateLimitReason === 'user_tpm_limit') {
      return `您的請求太頻繁，請稍後 ${remainingSec} 秒`
    }
    if (classification.rateLimitReason === 'global_tpm_limit') {
      return `系統目前繁忙，請稍後 ${remainingSec} 秒再試`
    }
    if (classification.allKeysExhausted) {
      return `目前 AI 服務繁忙，請等待 ${remainingSec} 秒後重試，或切換至其他模型`
    }
    return `系統繁忙，將在 ${remainingSec} 秒後自動重試...`
  }
  return `⏳ 連線重試中，${remainingSec} 秒後進行第 ${attempt} 次重試...`
}

/**
 * Chat Composable
 *
 * Encapsulates streaming chat logic, command detection, and error handling.
 * Extracts the complex streaming implementation from App.vue.
 */
export function useChat() {
  const MAX_MESSAGE_LENGTH = CHAT_CONFIG.maxMessageLength || 8000

  // ========== Stores ==========
  const chatStore = useChatStore()
  const conversationStore = useConversationStore()
  const systemStore = useSystemStore()

  const { messages, userInput, isProcessing, model } = storeToRefs(chatStore)
  const { currentConversationId } = storeToRefs(conversationStore)
  const { statusMessage } = storeToRefs(systemStore)

  // ========== Local State ==========
  const abortController = ref(null)
  const isRetrying = ref(false)
  const retryCountdown = ref({
    active: false,
    type: '',
    remainingSec: 0,
    totalSec: 0,
  })
  // Holds the resolve fn of the "skip delay" promise during a countdown
  let _skipDelayResolve = null

  function extractExclamationCommand(message) {
    if (typeof message !== 'string') return null
    const trimmed = message.trim()
    if (!trimmed.startsWith('!')) return null
    return trimmed.slice(1).trim()
  }

  // ========== Helper Functions ==========

  /**
   * Process a single SSE event "data" payload (already reconstructed).
   * @param {string} data - SSE event data payload (may contain newlines)
   * @param {Object} aiMsgObj - AI message object to update
   */
  function processEventData(data, aiMsgObj) {
    if (data == null) return

    // Handle status messages
    if (data.trim().startsWith('[STATUS:')) {
      return
    }

    // Clear status message when receiving content
    if (statusMessage.value) {
      statusMessage.value = ''
    }

    // Preserve blank events as newline tokens (rare, but possible).
    aiMsgObj.content += (data === '' ? '\n' : data)

    const rateLimitMarker = extractRateLimitMarker(aiMsgObj.content)
    if (rateLimitMarker) {
      aiMsgObj.content = rateLimitMarker.cleanedContent
      const err = new Error('SSE rate limit marker received')
      err.httpStatus = 429
      err.retryAfterSec = rateLimitMarker.retryAfterSec
      err.remainingKeyCount = rateLimitMarker.remainingKeyCount
      throw err
    }

    const marker = extractCommandMarker(aiMsgObj.content)
    if (marker) {
      aiMsgObj.command = { content: marker.command, status: 'pending', createdAt: Date.now() }
      aiMsgObj.content = marker.cleanedContent
    }
  }

  function handleAbortedStream(isRateLimitContext, aiMsgObj) {
    if (isRateLimitContext && (!aiMsgObj.content || !aiMsgObj.content.trim())) {
      aiMsgObj.content = '⏹️ 已取消自動重試。'
    } else {
      markAbortedResponse(aiMsgObj)
    }
    resetRetryIndicators()
    return { status: 'aborted' }
  }

  function markAbortedResponse(aiMsgObj) {
    aiMsgObj.aborted = true
    if (typeof aiMsgObj.content !== 'string' || !aiMsgObj.content) {
      aiMsgObj.content = '[已中斷回應]'
      return
    }
    if (!aiMsgObj.content.includes('[已中斷回應]')) {
      aiMsgObj.content = `${aiMsgObj.content}\n[已中斷回應]`
    }
  }

  /**
   * Handle streaming response from server
   * @param {Response} response - Fetch response object
   * @param {Object} aiMsgObj - AI message object to populate
   * @param {AbortSignal|null} signal - Abort signal for stopping stream read loop
   */
  async function handleStream(response, aiMsgObj, signal = null) {
    if (!response.ok) {
      const err = new Error(`HTTP ${response.status}`)
      err.httpStatus = response.status
      throw err
    }

    const contentType = response.headers.get('Content-Type') || ''
    const mimeType = contentType.split(';', 1)[0].trim().toLowerCase()
    if (mimeType !== 'text/event-stream') {
      const err = new Error(`Unexpected Content-Type: ${contentType || '(empty)'}`)
      err.httpStatus = response.status
      throw err
    }

    if (!response.body) {
      const err = new Error('Empty response body for SSE stream')
      err.httpStatus = response.status
      throw err
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventDataLines = []
    const onAbort = () => {
      reader.cancel().catch(() => {
        // Ignore cancellation race errors during abort
      })
    }

    try {
      signal?.addEventListener('abort', onAbort, { once: true })
      let isAborted = false
      while (true) {
        if (signal?.aborted) {
          isAborted = true
          try {
            await reader.cancel()
          } catch (cancelError) {
            // Ignore cancellation race errors during abort
          }
          break
        }

        const { value, done } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() // Keep last incomplete line

        for (const rawLine of lines) {
          const line = rawLine.replace(/\r$/, '')

          // Blank line terminates the current SSE event.
          if (line === '') {
            if (eventDataLines.length > 0) {
              processEventData(eventDataLines.join('\n'), aiMsgObj)
              eventDataLines = []
            }
            continue
          }

          // Accumulate multi-line "data:" fields within the same SSE event.
          if (line.startsWith('data:')) {
            eventDataLines.push(line.slice(5).replace(/^ /, ''))
          }
        }
      }

      if (isAborted) {
        throw new DOMException('Aborted', 'AbortError')
      }

      // Process remaining buffer line (if any)
      if (buffer) {
        const line = buffer.replace(/\r$/, '')
        if (line.startsWith('data:')) {
          eventDataLines.push(line.slice(5).replace(/^ /, ''))
        } else if (line === '' && eventDataLines.length > 0) {
          processEventData(eventDataLines.join('\n'), aiMsgObj)
          eventDataLines = []
        }
      }

      // Flush the last unterminated event (common when the server closes the stream).
      if (eventDataLines.length > 0) {
        processEventData(eventDataLines.join('\n'), aiMsgObj)
        eventDataLines = []
      }

      // Validate response - ensure AI responded with content
      const hasCommand = aiMsgObj.command ||
        hasPendingCommandMarker(aiMsgObj.content)

      if ((!aiMsgObj.content || !aiMsgObj.content.trim()) && !hasCommand) {
        aiMsgObj.content = '⚠️ (系統提示：AI 未回傳任何內容，請稍後再試或檢查後端日誌。)'
      }
    } catch (error) {
      try {
        await reader.cancel()
      } catch {
        // Ignore stream cancellation errors when bubbling up original failure.
      }
      throw error
    } finally {
      signal?.removeEventListener('abort', onAbort)
      reader.releaseLock()
    }
  }

  // ========== Retry Logic ==========

  /**
   * Sleep for `ms` milliseconds, but reject immediately if `signal` is aborted.
   * @param {number} ms
   * @param {AbortSignal|null|undefined} signal
   */
  function abortableDelay(ms, signal) {
    return new Promise((resolve, reject) => {
      if (signal?.aborted) {
        reject(new DOMException('Aborted', 'AbortError'))
        return
      }
      const id = setTimeout(resolve, ms)
      signal?.addEventListener('abort', () => {
        clearTimeout(id)
        reject(new DOMException('Aborted', 'AbortError'))
      }, { once: true })
    })
  }

  function resetRetryIndicators() {
    isRetrying.value = false
    statusMessage.value = ''
    retryCountdown.value = {
      active: false,
      type: '',
      remainingSec: 0,
      totalSec: 0,
    }
    _skipDelayResolve = null
  }

  /**
   * Send a chat request with automatic retry for transient errors (429, network).
   * Returns `{ status: 'success'|'aborted'|'failed' }`.
   * Non-retryable errors write to aiMsgObj.content.
   */
  async function sendWithRetry(params, aiMsgObj) {
    resetRetryIndicators()
    let boundedRetryAttempt = 0
    let lastRetryType = null
    // Capture signal once — stopStreaming() sets abortController.value = null,
    // so re-reading it inside the loop would see null and miss the abort.
    const signal = abortController.value?.signal ?? null

    while (true) {
      try {
        const response = await chatApi.streamChat(params, signal)

        if (!response.ok) {
          const retryAfterSecFromHeader = readRetryAfterSecFromHeaders(response.headers)
          const err = new Error(`HTTP ${response.status}`)
          err.httpStatus = response.status
          const { errorCode, rateLimitReason, retryAfterSec } = await readErrorMetadataFromResponse(response)
          if (errorCode) err.errorCode = errorCode
          if (rateLimitReason) err.rateLimitReason = rateLimitReason
          if (retryAfterSecFromHeader != null) err.retryAfterSec = retryAfterSecFromHeader
          else if (retryAfterSec != null) err.retryAfterSec = retryAfterSec
          throw err
        }

        await handleStream(response, aiMsgObj, signal)
        return { status: 'success' }

      } catch (error) {
        if (error.name === 'AbortError') {
          return handleAbortedStream(lastRetryType === 'rateLimit', aiMsgObj)
        }

        const resolvedHttpStatus = resolveHttpStatus(error)
        hydrateRateLimitMetadata(error, resolvedHttpStatus)
        const classified = classifyError(error, resolvedHttpStatus)
        if (classified.type === 'rateLimit') {
          console.warn('Stream throttled by rate limit; scheduling automatic retry.', error)
        } else {
          console.error(`Stream error (attempt ${boundedRetryAttempt}):`, error)
        }

        const isRateLimit = classified.type === 'rateLimit'
        const exceededRetryBudget = !isRateLimit && boundedRetryAttempt >= MAX_RETRIES

        if (!classified.retryable || exceededRetryBudget) {
          const delaySec = classified.delayMs > 0 ? Math.ceil(classified.delayMs / 1000) : 0
          aiMsgObj.content = buildErrorMessage(classified.type, boundedRetryAttempt, delaySec, {
            allKeysExhausted: classified.allKeysExhausted,
            rateLimitReason: classified.rateLimitReason,
          })
          resetRetryIndicators()
          return { status: 'failed', errorType: classified.type }
        }

        if (!isRateLimit) {
          boundedRetryAttempt++
        }
        lastRetryType = classified.type
        const delaySec = Math.ceil(classified.delayMs / 1000)

        // Countdown display — each 1-second tick is abort-aware; retryNow() skips remaining delay
        isRetrying.value = true
        const skipPromise = new Promise(resolve => { _skipDelayResolve = resolve })
        countdownLoop: for (let remaining = delaySec; remaining > 0; remaining--) {
          statusMessage.value = buildRetryStatusMessage(classified, remaining, boundedRetryAttempt)
          retryCountdown.value = {
            active: true,
            type: classified.type,
            remainingSec: remaining,
            totalSec: delaySec,
          }
          try {
            await Promise.race([abortableDelay(1000, signal), skipPromise])
          } catch (e) {
            if (e.name === 'AbortError') {
              return handleAbortedStream(classified.type === 'rateLimit', aiMsgObj)
            }
            throw e
          }
          // skipPromise resolved → exit countdown immediately
          if (_skipDelayResolve === null) break countdownLoop
        }
        resetRetryIndicators()
        aiMsgObj.content = ''
      }
    }
  }

  // ========== Main Send Function ==========

  /**
   * Send message and handle streaming response
   * @param {string|null} content - Message content (uses userInput if null)
   */
  async function sendMessage(content = null) {
    const useDraftInput = content == null
    const draftBeforeSend = useDraftInput ? userInput.value : ''
    const msg = (typeof content === 'string' && content)
      ? content
      : userInput.value.trim()

    if (!msg || isProcessing.value) return

    if (msg.length > MAX_MESSAGE_LENGTH) {
      statusMessage.value = `訊息過長（${msg.length} 字元），上限為 ${MAX_MESSAGE_LENGTH} 字元`
      return
    }

    const exclamationCommand = extractExclamationCommand(msg)
    if (exclamationCommand !== null) {
      if (!exclamationCommand) {
        statusMessage.value = '請在 ! 後輸入 Linux 指令，例如 !docker ps'
        return
      }
    }

    // Set immediately to prevent double-send from rapid clicks
    isProcessing.value = true
    statusMessage.value = ''

    // Get conversation ID from backend if new chat
    let isNewConversation = false
    const sourceConversationId = currentConversationId.value
    if (!currentConversationId.value) {
      try {
        const result = await chatApi.createConversation()
        const createdConversationId = typeof result?.data === 'string' ? result.data.trim() : ''
        if (!createdConversationId) {
          throw new Error('建立對話失敗：缺少 conversationId')
        }
        chatStore.moveConversationDraft(sourceConversationId, createdConversationId)
        conversationStore.selectConversation(createdConversationId)
        chatStore.setActiveDraftConversation(createdConversationId)
        isNewConversation = true
      } catch (error) {
        console.error('建立對話失敗:', error)
        isProcessing.value = false
        statusMessage.value = '無法建立對話，請稍後再試。'
        return
      }
    }

    // Add user message and AI placeholder in one reactive update
    messages.value.push({ role: 'user', content: msg }, { role: 'ai', content: '' })
    const aiMsgObj = messages.value[messages.value.length - 1]

    // Create abort controller for this request
    abortController.value = new AbortController()

    try {
      const params = {
        message: msg,
        conversationId: currentConversationId.value || null,
        model: model.value,
      }
      const result = await sendWithRetry(params, aiMsgObj)
      if (useDraftInput) {
        if (result?.status === 'success') {
          if (userInput.value === draftBeforeSend) {
            userInput.value = ''
          }
        } else if (!userInput.value.trim()) {
          userInput.value = draftBeforeSend
        }
      }
    } finally {
      // Cleanup
      isProcessing.value = false
      abortController.value = null
      statusMessage.value = ''

      // Refresh conversations list only for new conversations
      if (isNewConversation) {
        try {
          await conversationStore.loadConversations()
        } catch (error) {
          console.error('更新對話列表失敗', error)
        }
      }
    }
  }

  /**
   * Skip the current retry countdown and retry immediately
   */
  function retryNow() {
    if (_skipDelayResolve) {
      const resolve = _skipDelayResolve
      _skipDelayResolve = null
      resolve()
    }
  }

  /**
   * Stop current streaming response
   */
  function stopStreaming() {
    if (abortController.value) {
      abortController.value.abort()
      abortController.value = null
    }
  }

  // ========== Return Public API ==========
  return {
    sendMessage,
    stopStreaming,
    retryNow,
    isRetrying,
    retryCountdown,
  }
}
