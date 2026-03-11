import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useChat } from '../useChat.js'
import { chatApi } from '../../api/chatApi.js'
import { CHAT_CONFIG } from '../../config/app.config.js'
import { useChatStore } from '../../stores/chatStore.js'
import { useConversationStore } from '../../stores/conversationStore.js'
import { useSystemStore } from '../../stores/systemStore.js'

async function flushPromises() {
  // process.nextTick fires after ALL pending microtasks (including
  // Response.text() → Blob.text() → ReadableStream async chains).
  // Unlike Promise.resolve(), it is NOT mocked by vi.useFakeTimers().
  for (let i = 0; i < 3; i++) {
    await new Promise(resolve => process.nextTick(resolve))
  }
}

describe('useChat', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  it('rejects message over max length without sending API requests', async () => {
    const streamSpy = vi.spyOn(chatApi, 'streamChat')
    const createConversationSpy = vi.spyOn(chatApi, 'createConversation')

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const systemStore = useSystemStore()
    const { sendMessage } = useChat()

    conversationStore.selectConversation('conv-1')
    chatStore.userInput = 'x'.repeat((CHAT_CONFIG.maxMessageLength || 8000) + 1)

    await sendMessage()

    expect(chatStore.messages).toHaveLength(0)
    expect(chatStore.isProcessing).toBe(false)
    expect(systemStore.statusMessage).toContain('訊息過長')
    expect(streamSpy).not.toHaveBeenCalled()
    expect(createConversationSpy).not.toHaveBeenCalled()
  })

  it('sends user input and appends streamed AI response', async () => {
    const streamSpy = vi
      .spyOn(chatApi, 'streamChat')
      .mockResolvedValue(new Response('data: 測試回覆\n\n', {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const { sendMessage } = useChat()

    conversationStore.selectConversation('conv-1')
    chatStore.userInput = 'Hello from test'

    await sendMessage()

    expect(streamSpy).toHaveBeenCalledTimes(1)
    expect(streamSpy.mock.calls[0][0]).toMatchObject({
      message: 'Hello from test',
      conversationId: 'conv-1',
    })
    expect(chatStore.userInput).toBe('')
    expect(chatStore.messages).toHaveLength(2)
    expect(chatStore.messages[0]).toMatchObject({ role: 'user', content: 'Hello from test' })
    expect(chatStore.messages[1]).toMatchObject({ role: 'ai', content: '測試回覆' })
  })

  it('moves draft from new conversation bucket to created conversation before sending', async () => {
    vi.spyOn(chatApi, 'createConversation').mockResolvedValue({ data: 'conv-new' })
    const streamSpy = vi
      .spyOn(chatApi, 'streamChat')
      .mockResolvedValue(new Response('data: 已建立新對話\n\n', {
        status: 200,
        headers: { 'Content-Type': 'text/event-stream' },
      }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    vi.spyOn(conversationStore, 'loadConversations').mockResolvedValue([])
    const { sendMessage } = useChat()

    conversationStore.createNewConversation()
    chatStore.setActiveDraftConversation('')
    chatStore.userInput = 'Draft on new conversation'

    await sendMessage()

    expect(streamSpy).toHaveBeenCalledTimes(1)
    expect(streamSpy.mock.calls[0][0]).toMatchObject({
      message: 'Draft on new conversation',
      conversationId: 'conv-new',
    })
    expect(conversationStore.currentConversationId).toBe('conv-new')
    expect(chatStore.getConversationDraft('')).toBe('')
    expect(chatStore.getConversationDraft('conv-new')).toBe('')
  })

  it('auto-retries 429 with retry-after countdown and succeeds without losing draft handling', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response('', {
          status: 429,
          headers: { 'Retry-After': '1' },
        }))
        .mockResolvedValueOnce(new Response('data: 重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage, isRetrying, retryCountdown } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = '需要重試'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(isRetrying.value).toBe(true)
      expect(systemStore.statusMessage).toContain('將在 1 秒後自動重試')
      expect(retryCountdown.value).toMatchObject({
        active: true,
        type: 'rateLimit',
        remainingSec: 1,
        totalSec: 1,
      })

      await vi.advanceTimersByTimeAsync(1000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('重試成功')
      expect(chatStore.userInput).toBe('')
      expect(chatStore.isProcessing).toBe(false)
      expect(systemStore.statusMessage).toBe('')
      expect(isRetrying.value).toBe(false)
      expect(retryCountdown.value).toMatchObject({
        active: false,
        remainingSec: 0,
        totalSec: 0,
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('uses user-rate-limit status copy when backend returns user_rate_limit reason', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response(JSON.stringify({
          success: false,
          message: '請求過於頻繁，請稍後再試。',
          error: { message: '請求過於頻繁，請稍後再試。', code: 'RATE_LIMIT_EXCEEDED' },
          data: { reason: 'user_rate_limit' },
        }), {
          status: 429,
          headers: {
            'Content-Type': 'application/json',
            'Retry-After': '2',
          },
        }))
        .mockResolvedValueOnce(new Response('data: 重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = 'user rate limit'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(systemStore.statusMessage).toContain('您的請求太頻繁，請稍後 2 秒')

      await vi.advanceTimersByTimeAsync(2000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('重試成功')
    } finally {
      vi.useRealTimers()
    }
  })

  it('uses global-TPM status copy when backend returns global_tpm_limit reason', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response(JSON.stringify({
          success: false,
          message: '請求過於頻繁，請稍後再試。',
          error: { message: '請求過於頻繁，請稍後再試。', code: 'RATE_LIMIT_EXCEEDED' },
          data: { reason: 'global_tpm_limit' },
        }), {
          status: 429,
          headers: {
            'Content-Type': 'application/json',
            'Retry-After': '2',
          },
        }))
        .mockResolvedValueOnce(new Response('data: 重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = 'global tpm limit'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(systemStore.statusMessage).toContain('系統目前繁忙，請稍後 2 秒再試')

      await vi.advanceTimersByTimeAsync(2000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('重試成功')
    } finally {
      vi.useRealTimers()
    }
  })

  it('auto-retries 429 using details.retryAfterSeconds when Retry-After header is absent', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response(JSON.stringify({
          success: false,
          message: '請求過於頻繁，請稍後再試。',
          error: { message: '請求過於頻繁，請稍後再試。', code: 'RATE_LIMIT_EXCEEDED' },
          data: {
            reason: 'global_tpm_limit',
            details: { retryAfterSeconds: 2 },
          },
        }), {
          status: 429,
          headers: { 'Content-Type': 'application/json' },
        }))
        .mockResolvedValueOnce(new Response('data: details 重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = 'details retry-after'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(systemStore.statusMessage).toContain('系統目前繁忙，請稍後 2 秒再試')

      await vi.advanceTimersByTimeAsync(2000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('details 重試成功')
    } finally {
      vi.useRealTimers()
    }
  })

  it('auto-retries 429 when axios-style error object exposes details.retryAfterSeconds', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockRejectedValueOnce(Object.assign(new Error('Too Many Requests'), {
          response: {
            status: 429,
            headers: {},
            data: {
              reason: 'user_tpm_limit',
              details: { retryAfterSeconds: 2 },
            },
          },
        }))
        .mockResolvedValueOnce(new Response('data: axios details 重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = 'axios details retry-after'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(systemStore.statusMessage).toContain('您的請求太頻繁，請稍後 2 秒')

      await vi.advanceTimersByTimeAsync(2000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('axios details 重試成功')
    } finally {
      vi.useRealTimers()
    }
  })

  it('auto-retries when stream error exposes status and lowercase retry-after headers', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockRejectedValueOnce(Object.assign(new Error('Too Many Requests'), {
          status: 429,
          headers: { 'retry-after': '1' },
        }))
        .mockResolvedValueOnce(new Response('data: 錯誤物件重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage, isRetrying, retryCountdown } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = '測試 error.status'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(isRetrying.value).toBe(true)
      expect(systemStore.statusMessage).toContain('將在 1 秒後自動重試')
      expect(retryCountdown.value).toMatchObject({
        active: true,
        type: 'rateLimit',
        remainingSec: 1,
        totalSec: 1,
      })

      await vi.advanceTimersByTimeAsync(1000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('錯誤物件重試成功')
      expect(systemStore.statusMessage).toBe('')
      expect(isRetrying.value).toBe(false)
      expect(retryCountdown.value).toMatchObject({
        active: false,
        remainingSec: 0,
        totalSec: 0,
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('auto-retries when SSE stream emits rate-limit marker', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response('data: [RATE_LIMIT:::2:::]\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))
        .mockResolvedValueOnce(new Response('data: 標記重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage, isRetrying, retryCountdown } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = '測試 marker 重試'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(isRetrying.value).toBe(true)
      expect(systemStore.statusMessage).toContain('將在 2 秒後自動重試')
      expect(retryCountdown.value).toMatchObject({
        active: true,
        type: 'rateLimit',
        remainingSec: 2,
        totalSec: 2,
      })

      await vi.advanceTimersByTimeAsync(2000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('標記重試成功')
      expect(chatStore.userInput).toBe('')
      expect(chatStore.isProcessing).toBe(false)
      expect(systemStore.statusMessage).toBe('')
      expect(isRetrying.value).toBe(false)
      expect(retryCountdown.value).toMatchObject({
        active: false,
        remainingSec: 0,
        totalSec: 0,
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows actionable status copy when SSE rate-limit marker indicates all keys exhausted', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response('data: [RATE_LIMIT:::2:::0:::]\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))
        .mockResolvedValueOnce(new Response('data: key 恢復後重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage, isRetrying, retryCountdown } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = '測試 key 全滿'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(isRetrying.value).toBe(true)
      expect(systemStore.statusMessage).toContain('目前 AI 服務繁忙，請等待 2 秒後重試，或切換至其他模型')
      expect(retryCountdown.value).toMatchObject({
        active: true,
        type: 'rateLimit',
        remainingSec: 2,
        totalSec: 2,
      })

      await vi.advanceTimersByTimeAsync(2000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('key 恢復後重試成功')
      expect(systemStore.statusMessage).toBe('')
      expect(isRetrying.value).toBe(false)
      expect(retryCountdown.value).toMatchObject({
        active: false,
        remainingSec: 0,
        totalSec: 0,
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('applies jitter to 429 retry delay to spread retries', async () => {
    vi.useFakeTimers()
    try {
      vi.spyOn(Math, 'random').mockReturnValue(0.5)
      const streamSpy = vi
        .spyOn(chatApi, 'streamChat')
        .mockResolvedValueOnce(new Response('', {
          status: 429,
          headers: { 'Retry-After': '1' },
        }))
        .mockResolvedValueOnce(new Response('data: jitter 重試成功\n\n', {
          status: 200,
          headers: { 'Content-Type': 'text/event-stream' },
        }))

      const chatStore = useChatStore()
      const conversationStore = useConversationStore()
      const systemStore = useSystemStore()
      const { sendMessage, isRetrying } = useChat()

      conversationStore.selectConversation('conv-1')
      chatStore.userInput = '測試 jitter'

      const sendPromise = sendMessage()
      await flushPromises()

      expect(streamSpy).toHaveBeenCalledTimes(1)
      expect(isRetrying.value).toBe(true)
      expect(systemStore.statusMessage).toContain('將在 3 秒後自動重試')

      await vi.advanceTimersByTimeAsync(3000)
      await sendPromise

      expect(streamSpy).toHaveBeenCalledTimes(2)
      expect(chatStore.messages[1].content).toBe('jitter 重試成功')
      expect(systemStore.statusMessage).toBe('')
      expect(isRetrying.value).toBe(false)
    } finally {
      vi.useRealTimers()
    }
  })

  it('does not auto-retry 429 when backend returns concurrent stream error code', async () => {
    const streamSpy = vi.spyOn(chatApi, 'streamChat').mockResolvedValue(new Response(JSON.stringify({
      success: false,
      message: '您已有對話進行中，請等待完成。',
      error: { message: '您已有對話進行中，請等待完成。', code: 'CONCURRENT_STREAM_LIMIT_EXCEEDED' },
      data: { maxConcurrentStreams: 1 },
    }), {
      status: 429,
      headers: {
        'Content-Type': 'application/json',
        'Retry-After': '15',
      },
    }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const systemStore = useSystemStore()
    const { sendMessage, isRetrying } = useChat()

    conversationStore.selectConversation('conv-1')
    chatStore.userInput = '同時開啟第二個分頁'

    await sendMessage()

    expect(streamSpy).toHaveBeenCalledTimes(1)
    expect(chatStore.messages).toHaveLength(2)
    expect(chatStore.messages[1].content).toBe('⚠️ 您已有對話進行中，請等待其完成後再發送。')
    expect(chatStore.userInput).toBe('同時開啟第二個分頁')
    expect(systemStore.statusMessage).toBe('')
    expect(isRetrying.value).toBe(false)
  })

  it('lets user cancel 429 auto-retry and keeps input draft', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0)
    vi.spyOn(chatApi, 'streamChat').mockResolvedValue(new Response('', {
      status: 429,
      headers: { 'Retry-After': '10' },
    }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const { sendMessage, stopStreaming, isRetrying, retryCountdown } = useChat()

    conversationStore.selectConversation('conv-1')
    chatStore.userInput = '稍後再試'

    const sendPromise = sendMessage()
    await flushPromises()
    expect(isRetrying.value).toBe(true)

    stopStreaming()
    await sendPromise

    expect(chatStore.messages).toHaveLength(2)
    expect(chatStore.messages[1].content).toBe('⏹️ 已取消自動重試。')
    expect(chatStore.userInput).toBe('稍後再試')
    expect(chatStore.isProcessing).toBe(false)
    expect(isRetrying.value).toBe(false)
    expect(retryCountdown.value).toMatchObject({
      active: false,
      remainingSec: 0,
      totalSec: 0,
    })
  })

  it('rejects non-SSE content type to avoid rendering HTML error pages', async () => {
    vi.spyOn(chatApi, 'streamChat').mockResolvedValue(new Response('<html>Server Error</html>', {
      status: 200,
      headers: { 'Content-Type': 'text/html; charset=utf-8' },
    }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const { sendMessage } = useChat()

    conversationStore.selectConversation('conv-1')
    await sendMessage('trigger invalid content-type')

    expect(chatStore.messages).toHaveLength(2)
    expect(chatStore.messages[1].content).toBe('❌ 伺服器回應異常，請稍後再試。')
    expect(chatStore.messages[1].content).not.toContain('<html>')
  })

  it('keeps user draft when a send attempt fails', async () => {
    vi.spyOn(chatApi, 'streamChat').mockResolvedValue(new Response('<html>Server Error</html>', {
      status: 200,
      headers: { 'Content-Type': 'text/html; charset=utf-8' },
    }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const { sendMessage } = useChat()

    conversationStore.selectConversation('conv-1')
    chatStore.userInput = '失敗後應保留草稿'

    await sendMessage()

    expect(chatStore.userInput).toBe('失敗後應保留草稿')
    expect(chatStore.messages).toHaveLength(2)
    expect(chatStore.messages[1].content).toBe('❌ 伺服器回應異常，請稍後再試。')
  })

  it('stops stream reader when user aborts during an open stream', async () => {
    const encoder = new TextEncoder()
    let cancelCalled = false

    const openStream = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('data: 進行中\n\n'))
      },
      cancel() {
        cancelCalled = true
      },
    })

    vi.spyOn(chatApi, 'streamChat').mockResolvedValue(new Response(openStream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }))

    const chatStore = useChatStore()
    const conversationStore = useConversationStore()
    const { sendMessage, stopStreaming } = useChat()

    conversationStore.selectConversation('conv-1')

    const sendPromise = sendMessage('abort me')
    await Promise.resolve()
    stopStreaming()

    const result = await Promise.race([
      sendPromise.then(() => 'done'),
      new Promise((resolve) => setTimeout(() => resolve('timeout'), 200)),
    ])

    expect(result).toBe('done')
    expect(cancelCalled).toBe(true)
    expect(chatStore.messages[1].content).toContain('[已中斷回應]')
  })
})
