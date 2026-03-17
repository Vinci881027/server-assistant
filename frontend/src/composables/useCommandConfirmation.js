import { storeToRefs } from 'pinia'
import { useChatStore } from '../stores/chatStore'
import { useConversationStore } from '../stores/conversationStore'
import { useSystemStore } from '../stores/systemStore'
import httpClient from '../api/httpClient'
import { COMMAND_CONFIRM_TIMEOUT_SECONDS } from '../config/app.config'
import { extractOffloadJobMarker, extractBgJobMarker } from '../utils/commandMarkers'
import { useToastQueue } from './useToastQueue'

const COMMAND_CONFIRM_TIMEOUT_MS = COMMAND_CONFIRM_TIMEOUT_SECONDS * 1000
const COMMAND_TIMEOUT_MESSAGE = '已逾時，指令已取消'
const COMMAND_TIMEOUT_TOAST_MESSAGE = '⚠️ 指令確認逾時，已自動取消'
const OFFLOAD_POLL_FAILURE_TOAST_MESSAGE = '❌ Offload 進度查詢失敗，請稍後再試'
const COMMAND_JOB_POLL_FAILURE_TOAST_MESSAGE = '❌ 背景命令進度查詢失敗，請稍後再試'
const POLL_FAILURE_TOAST_DURATION_MS = 4500

export function useCommandConfirmation() {
  const chatStore = useChatStore()
  const conversationStore = useConversationStore()
  const systemStore = useSystemStore()
  const { info: showInfoToast } = useToastQueue()

  const { messages, isProcessing } = storeToRefs(chatStore)
  const { currentConversationId } = storeToRefs(conversationStore)

  const commandTimeoutHandles = new Map()
  let offloadPollToken = 0
  let commandJobPollToken = 0

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms))
  }

  function clearCommandTimeout(msg) {
    const timeoutId = commandTimeoutHandles.get(msg)
    if (timeoutId !== undefined) {
      window.clearTimeout(timeoutId)
      commandTimeoutHandles.delete(msg)
    }
  }

  function clearAllCommandTimeouts() {
    for (const timeoutId of commandTimeoutHandles.values()) {
      window.clearTimeout(timeoutId)
    }
    commandTimeoutHandles.clear()
  }

  function resetPollTokens() {
    offloadPollToken++
    commandJobPollToken++
  }

  function markCommandAsPending(msg, { resetCreatedAt = false } = {}) {
    if (!msg?.command) return
    msg.command.status = 'pending'
    if (resetCreatedAt) {
      msg.command.createdAt = Date.now()
    }
    delete msg.command.resolvedAt
    msg.command.timeoutAt = Date.now() + COMMAND_CONFIRM_TIMEOUT_MS
    msg.command.conversationId = currentConversationId.value || null
  }

  async function autoCancelTimedOutCommand(msg) {
    if (!msg?.command || msg.command.status !== 'pending' || msg.command.inFlight) return

    const timeoutConversationId = msg.command.conversationId ?? null
    msg.command.status = 'expired'
    msg.command.resolvedAt = Date.now()
    delete msg.command.timeoutAt
    delete msg.command.conversationId
    clearCommandTimeout(msg)
    messages.value.push({ role: 'ai', content: COMMAND_TIMEOUT_MESSAGE })
    showInfoToast(COMMAND_TIMEOUT_TOAST_MESSAGE)

    try {
      await httpClient.post('/ai/cancel-command', {
        conversationId: timeoutConversationId,
        command: msg.command.content,
      })
    } catch (e) {
      console.warn('逾時取消指令失敗:', e)
    }
  }

  function schedulePendingCommandTimeout(msg, { resetDeadline = false } = {}) {
    if (!msg?.command || msg.command.status !== 'pending' || msg.command.inFlight) {
      clearCommandTimeout(msg)
      return
    }

    if (
      resetDeadline ||
      typeof msg.command.timeoutAt !== 'number' ||
      !Number.isFinite(msg.command.timeoutAt)
    ) {
      msg.command.timeoutAt = Date.now() + COMMAND_CONFIRM_TIMEOUT_MS
    }
    if (!('conversationId' in msg.command)) {
      msg.command.conversationId = currentConversationId.value || null
    }

    const remainingMs = msg.command.timeoutAt - Date.now()
    if (remainingMs <= 0) {
      void autoCancelTimedOutCommand(msg)
      return
    }

    clearCommandTimeout(msg)
    const timeoutId = window.setTimeout(() => {
      void autoCancelTimedOutCommand(msg)
    }, remainingMs)
    commandTimeoutHandles.set(msg, timeoutId)
  }

  function buildOffloadProgressText(progress) {
    if (!progress || typeof progress !== 'object') return 'Offload 執行中...'
    const percent = typeof progress.percent === 'number' && progress.percent >= 0
      ? `${progress.percent}%`
      : '--%'
    const copied = progress.copiedSize || '未知'
    return `${percent}｜${copied}`
  }

  async function pollOffloadProgress(jobId, aiMsg, pollToken) {
    try {
      while (pollToken === offloadPollToken) {
        const resp = await httpClient.get(`/ai/offload-progress/${encodeURIComponent(jobId)}`)
        const progress = resp?.data?.data
        if (!progress) {
          throw new Error('無法取得 offload 進度')
        }

        systemStore.setStatusMessage(buildOffloadProgressText(progress))

        if (progress.done) {
          aiMsg.content = progress.result || progress.message || '✅ Offload 完成'
          return
        }

        await sleep(1000)
      }
    } catch (e) {
      if (pollToken === offloadPollToken) {
        aiMsg.content = '❌ Offload 進度查詢失敗: ' + (e.message || '未知錯誤')
        showPollFailureToast(OFFLOAD_POLL_FAILURE_TOAST_MESSAGE, e)
      }
    } finally {
      if (pollToken === offloadPollToken) {
        systemStore.clearStatusMessage()
      }
    }
  }

  async function pollCommandJobProgress(jobId, aiMsg, pollToken) {
    try {
      while (pollToken === commandJobPollToken) {
        const resp = await httpClient.get(`/ai/command-job-progress/${encodeURIComponent(jobId)}`)
        const progress = resp?.data?.data
        if (!progress) {
          throw new Error('無法取得背景命令進度')
        }

        systemStore.setStatusMessage(progress.message || '背景命令執行中...')

        if (progress.done) {
          aiMsg.content = progress.result || progress.message || '✅ 背景任務完成'
          return
        }

        await sleep(1000)
      }
    } catch (e) {
      if (pollToken === commandJobPollToken) {
        aiMsg.content = '❌ 背景命令進度查詢失敗: ' + (e.message || '未知錯誤')
        showPollFailureToast(COMMAND_JOB_POLL_FAILURE_TOAST_MESSAGE, e)
      }
    } finally {
      if (pollToken === commandJobPollToken) {
        systemStore.clearStatusMessage()
      }
    }
  }

  function showPollFailureToast(baseMessage, error) {
    if (typeof baseMessage !== 'string' || !baseMessage.trim()) return
    const reason = typeof error?.message === 'string' ? error.message.trim() : ''
    if (reason) {
      showInfoToast(`${baseMessage}（${reason}）`, POLL_FAILURE_TOAST_DURATION_MS)
      return
    }
    showInfoToast(baseMessage, POLL_FAILURE_TOAST_DURATION_MS)
  }

  /**
   * Handle command action (confirm/cancel)
   * Confirmation calls the backend directly to bypass AI model unreliability.
   */
  async function handleCommandAction(msg, action) {
    if (!msg?.command) return
    if (action === 'resend') {
      if (msg.command.status !== 'expired') return
      if (isProcessing.value || msg.command.inFlight) return
      markCommandAsPending(msg, { resetCreatedAt: true })
      schedulePendingCommandTimeout(msg, { resetDeadline: true })
      return
    }

    if (msg.command.status !== 'pending') return
    if (isProcessing.value || msg.command.inFlight) return

    clearCommandTimeout(msg)
    msg.command.inFlight = true

    if (action === 'confirm') {
      isProcessing.value = true
      msg.command.status = 'executing'
      messages.value.push({ role: 'ai', content: '' })
      const aiMsg = messages.value[messages.value.length - 1]
      try {
        const resp = await httpClient.post('/ai/confirm-command', {
          command: msg.command.content,
          conversationId: currentConversationId.value || null,
        })
        const apiResp = resp?.data
        if (apiResp && apiResp.success === false) {
          throw new Error(apiResp.message || '執行失敗')
        }
        const backendMessage =
          (typeof apiResp?.data === 'string' && apiResp.data.trim()) ||
          (typeof apiResp?.message === 'string' && apiResp.message.trim()) ||
          '✅ 指令已執行完成。'
        const offloadJobId = extractOffloadJobMarker(backendMessage)
        const commandJobId = extractBgJobMarker(backendMessage)
        msg.command.status = 'confirmed'
        msg.command.resolvedAt = Date.now()
        delete msg.command.timeoutAt
        delete msg.command.conversationId
        if (offloadJobId) {
          const pollToken = ++offloadPollToken
          aiMsg.content = `⏳ Offload 任務已啟動（Job ID: ${offloadJobId}）`
          void pollOffloadProgress(offloadJobId, aiMsg, pollToken)
        } else if (commandJobId) {
          const pollToken = ++commandJobPollToken
          aiMsg.content = `⏳ 背景任務已啟動（Job ID: ${commandJobId}）`
          void pollCommandJobProgress(commandJobId, aiMsg, pollToken)
        } else {
          aiMsg.content = backendMessage
        }
      } catch (e) {
        msg.command.status = 'failed'
        msg.command.resolvedAt = Date.now()
        delete msg.command.timeoutAt
        delete msg.command.conversationId
        aiMsg.content = '❌ 執行失敗: ' + (e.message || '未知錯誤')
      } finally {
        isProcessing.value = false
        msg.command.inFlight = false
      }
    } else {
      // Call backend directly instead of going through AI.
      // Sending '取消操作' through AI leaves confusing history that causes
      // the next deletion attempt to fail with an empty AI response.
      isProcessing.value = true
      try {
        await httpClient.post('/ai/cancel-command', {
          conversationId: currentConversationId.value || null,
          command: msg.command.content,
        })
        msg.command.status = 'cancelled'
        msg.command.resolvedAt = Date.now()
        delete msg.command.timeoutAt
        delete msg.command.conversationId
      } catch (e) {
        markCommandAsPending(msg)
        console.error('取消指令失敗:', e)
      } finally {
        isProcessing.value = false
        msg.command.inFlight = false
        if (msg.command.status === 'pending') {
          schedulePendingCommandTimeout(msg)
        }
      }
    }
  }

  return {
    commandTimeoutHandles,
    clearCommandTimeout,
    clearAllCommandTimeouts,
    schedulePendingCommandTimeout,
    handleCommandAction,
    resetPollTokens,
  }
}
