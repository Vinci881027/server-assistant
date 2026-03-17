import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useCommandConfirmation } from '../useCommandConfirmation.js'
import { useToastQueue } from '../useToastQueue.js'
import { useChatStore } from '../../stores/chatStore.js'
import { useConversationStore } from '../../stores/conversationStore.js'
import httpClient from '../../api/httpClient.js'

function createPendingCommandMessage(content = 'ls -la') {
  return {
    role: 'user',
    content: `請執行 ${content}`,
    command: {
      content,
      status: 'pending',
    },
  }
}

function createDeferred() {
  let resolve
  const promise = new Promise((res) => {
    resolve = res
  })
  return { promise, resolve }
}

describe('useCommandConfirmation', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    useToastQueue().dismissAll()
  })

  it('confirms a pending command by calling backend directly', async () => {
    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    conversationStore.selectConversation('conv-1')

    const postSpy = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: { success: true, data: '✅ 指令完成' },
    })

    const msg = createPendingCommandMessage('pwd')
    chatStore.messages = [msg]

    const { handleCommandAction } = useCommandConfirmation()
    await handleCommandAction(msg, 'confirm')

    expect(postSpy).toHaveBeenCalledWith('/ai/confirm-command', {
      command: 'pwd',
      conversationId: 'conv-1',
    })
    expect(msg.command.status).toBe('confirmed')
    expect(msg.command.inFlight).toBe(false)
    expect(msg.command.resolvedAt).toEqual(expect.any(Number))
    expect(msg.command.timeoutAt).toBeUndefined()
    expect(chatStore.isProcessing).toBe(false)
    expect(chatStore.messages.at(-1)).toMatchObject({
      role: 'ai',
      content: '✅ 指令完成',
    })
  })

  it('cancels a pending command by calling backend directly', async () => {
    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    conversationStore.selectConversation('conv-2')

    const postSpy = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: { success: true },
    })

    const msg = createPendingCommandMessage('rm /tmp/file')
    chatStore.messages = [msg]

    const { handleCommandAction } = useCommandConfirmation()
    await handleCommandAction(msg, 'cancel')

    expect(postSpy).toHaveBeenCalledWith('/ai/cancel-command', {
      conversationId: 'conv-2',
      command: 'rm /tmp/file',
    })
    expect(msg.command.status).toBe('cancelled')
    expect(msg.command.inFlight).toBe(false)
    expect(msg.command.resolvedAt).toEqual(expect.any(Number))
    expect(msg.command.timeoutAt).toBeUndefined()
    expect(chatStore.isProcessing).toBe(false)
  })

  it('auto-cancels timed-out pending command and appends timeout message', async () => {
    vi.useFakeTimers()
    try {
      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const queue = useToastQueue()
      conversationStore.selectConversation('conv-timeout')
      const postSpy = vi.spyOn(httpClient, 'post').mockResolvedValue({
        data: { success: true },
      })

      const msg = createPendingCommandMessage('dangerous-command')
      msg.command.timeoutAt = Date.now() + 10
      chatStore.messages = [msg]

      const { schedulePendingCommandTimeout } = useCommandConfirmation()
      schedulePendingCommandTimeout(msg)
      await vi.advanceTimersByTimeAsync(20)

      expect(postSpy).toHaveBeenCalledWith('/ai/cancel-command', {
        conversationId: 'conv-timeout',
        command: 'dangerous-command',
      })
      expect(msg.command.status).toBe('expired')
      expect(msg.command.timeoutAt).toBeUndefined()
      expect(chatStore.messages.at(-1)).toMatchObject({
        role: 'ai',
        content: '已逾時，指令已取消',
      })
      expect(queue.toasts.value).toHaveLength(1)
      expect(queue.toasts.value[0]).toMatchObject({
        type: 'info',
        message: '⚠️ 指令確認逾時，已自動取消',
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('marks command as failed when confirm endpoint returns error', async () => {
    const chatStore = useChatStore()
    const postSpy = vi.spyOn(httpClient, 'post').mockRejectedValue(new Error('permission denied'))

    const msg = createPendingCommandMessage('chmod 000 /tmp/file')
    chatStore.messages = [msg]

    const { handleCommandAction } = useCommandConfirmation()
    await handleCommandAction(msg, 'confirm')

    expect(postSpy).toHaveBeenCalledWith('/ai/confirm-command', {
      command: 'chmod 000 /tmp/file',
      conversationId: null,
    })
    expect(msg.command.status).toBe('failed')
    expect(msg.command.resolvedAt).toEqual(expect.any(Number))
    expect(msg.command.timeoutAt).toBeUndefined()
    expect(chatStore.messages.at(-1)).toMatchObject({
      role: 'ai',
      content: '❌ 執行失敗: permission denied',
    })
  })

  it('shows executing status while waiting for confirm endpoint response', async () => {
    const chatStore = useChatStore()
    const deferred = createDeferred()
    const postSpy = vi.spyOn(httpClient, 'post').mockReturnValue(deferred.promise)

    const msg = createPendingCommandMessage('pwd')
    chatStore.messages = [msg]

    const { handleCommandAction } = useCommandConfirmation()
    const pendingPromise = handleCommandAction(msg, 'confirm')

    expect(postSpy).toHaveBeenCalledTimes(1)
    expect(msg.command.status).toBe('executing')
    expect(msg.command.inFlight).toBe(true)

    deferred.resolve({ data: { success: true, data: '✅ 指令完成' } })
    await pendingPromise
    expect(msg.command.status).toBe('confirmed')
  })

  it('resets expired command back to pending when resend action is triggered', async () => {
    vi.useFakeTimers()
    try {
      const now = new Date('2026-03-12T09:00:00Z').getTime()
      vi.setSystemTime(now)

      const msg = createPendingCommandMessage('rm -rf /tmp/demo')
      msg.command.status = 'expired'
      msg.command.resolvedAt = now - 1000

      const { handleCommandAction } = useCommandConfirmation()
      await handleCommandAction(msg, 'resend')

      expect(msg.command.status).toBe('pending')
      expect(msg.command.createdAt).toBe(now)
      expect(msg.command.resolvedAt).toBeUndefined()
      expect(msg.command.timeoutAt).toBeGreaterThan(now)
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows global toast when offload polling fails', async () => {
    const chatStore = useChatStore()
    const queue = useToastQueue()
    const postSpy = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: { success: true, data: '⏳ Offload 任務已啟動 [OFFLOAD_JOB:::job-offload-1:::]' },
    })
    const getSpy = vi.spyOn(httpClient, 'get').mockRejectedValue(new Error('network down'))

    const msg = createPendingCommandMessage('offload-now')
    chatStore.messages = [msg]

    const { handleCommandAction } = useCommandConfirmation()
    await handleCommandAction(msg, 'confirm')
    await Promise.resolve()

    expect(postSpy).toHaveBeenCalledWith('/ai/confirm-command', {
      command: 'offload-now',
      conversationId: null,
    })
    expect(getSpy).toHaveBeenCalledWith('/ai/offload-progress/job-offload-1')
    expect(chatStore.messages.at(-1)).toMatchObject({
      role: 'ai',
      content: '❌ Offload 進度查詢失敗: network down',
    })
    expect(queue.toasts.value).toHaveLength(1)
    expect(queue.toasts.value[0]).toMatchObject({
      type: 'info',
      message: '❌ Offload 進度查詢失敗，請稍後再試（network down）',
      duration: 4500,
    })
  })

  it('shows global toast when background command polling fails', async () => {
    const chatStore = useChatStore()
    const queue = useToastQueue()
    const postSpy = vi.spyOn(httpClient, 'post').mockResolvedValue({
      data: { success: true, data: '⏳ 背景任務已啟動 [BG_JOB:::job-bg-1:::]' },
    })
    const getSpy = vi.spyOn(httpClient, 'get').mockRejectedValue(new Error('request timeout'))

    const msg = createPendingCommandMessage('run-bg-task')
    chatStore.messages = [msg]

    const { handleCommandAction } = useCommandConfirmation()
    await handleCommandAction(msg, 'confirm')
    await Promise.resolve()

    expect(postSpy).toHaveBeenCalledWith('/ai/confirm-command', {
      command: 'run-bg-task',
      conversationId: null,
    })
    expect(getSpy).toHaveBeenCalledWith('/ai/command-job-progress/job-bg-1')
    expect(chatStore.messages.at(-1)).toMatchObject({
      role: 'ai',
      content: '❌ 背景命令進度查詢失敗: request timeout',
    })
    expect(queue.toasts.value).toHaveLength(1)
    expect(queue.toasts.value[0]).toMatchObject({
      type: 'info',
      message: '❌ 背景命令進度查詢失敗，請稍後再試（request timeout）',
      duration: 4500,
    })
  })
})
