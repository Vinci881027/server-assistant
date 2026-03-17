import { createApp, h, nextTick, reactive } from 'vue'
import MessageList from '../MessageList.vue'

const originalResizeObserver = globalThis.ResizeObserver
const originalGetBCR = Element.prototype.getBoundingClientRect

beforeAll(() => {
  if (typeof globalThis.ResizeObserver === 'undefined') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }

  // Virtual scroller needs elements to have measurable dimensions in jsdom.
  // Override getBoundingClientRect so the virtualizer can compute layout.
  Element.prototype.getBoundingClientRect = function () {
    return { top: 0, left: 0, bottom: 800, right: 600, width: 600, height: 800, x: 0, y: 0 }
  }
})

afterAll(() => {
  Element.prototype.getBoundingClientRect = originalGetBCR

  if (typeof originalResizeObserver === 'undefined') {
    delete globalThis.ResizeObserver
    return
  }
  globalThis.ResizeObserver = originalResizeObserver
})

/**
 * Patch layout properties that @tanstack/vue-virtual needs but jsdom doesn't provide.
 * Must be called after app.mount() so the DOM elements exist.
 */
function patchVirtualScrollLayout(container) {
  const scrollEl = container.querySelector('.message-list-container')
  if (scrollEl) {
    Object.defineProperty(scrollEl, 'clientHeight', { configurable: true, value: 800 })
    Object.defineProperty(scrollEl, 'scrollHeight', { configurable: true, value: 2000 })
    Object.defineProperty(scrollEl, 'offsetHeight', { configurable: true, value: 800 })
  }
  // Patch virtual row items so measureElement returns non-zero height
  for (const row of container.querySelectorAll('[data-index]')) {
    Object.defineProperty(row, 'offsetHeight', { configurable: true, value: 120 })
  }
}

function mountMessageList(initialState = {}, handlerOverrides = {}) {
  const state = reactive({
    messages: [],
    isProcessing: false,
    isAdmin: false,
    isOnline: true,
    userInput: '',
    model: '20b',
    availableModels: {
      '20b': {
        label: 'GPT OSS 20B',
        category: 'High Intelligence',
        available: true,
      },
    },
    statusMessage: '',
    isRetrying: false,
    retryCountdown: {
      active: false,
      type: '',
      remainingSec: 0,
      totalSec: 0,
    },
    canRegenerateMessage: null,
    hasMoreFromServer: false,
    isHistoryLoading: false,
    historyLoadFailed: false,
    isLoadingMore: false,
    ...initialState,
  })

  const handlers = {
    onRetry: () => {},
    onCancelRetry: () => {},
    onCommandAction: () => {},
    onSend: () => {},
    onStop: () => {},
    onEditMessage: () => {},
    onRegenerateMessage: () => {},
    onLoadMore: () => {},
    onRetryHistory: () => {},
    ...handlerOverrides,
  }

  const container = document.createElement('div')
  document.body.appendChild(container)

  const app = createApp({
    render() {
      return h(MessageList, {
        messages: state.messages,
        isProcessing: state.isProcessing,
        isAdmin: state.isAdmin,
        isOnline: state.isOnline,
        userInput: state.userInput,
        model: state.model,
        availableModels: state.availableModels,
        statusMessage: state.statusMessage,
        isRetrying: state.isRetrying,
        retryCountdown: state.retryCountdown,
        canRegenerateMessage: state.canRegenerateMessage,
        hasMoreFromServer: state.hasMoreFromServer,
        isHistoryLoading: state.isHistoryLoading,
        historyLoadFailed: state.historyLoadFailed,
        isLoadingMore: state.isLoadingMore,
        onRetry: handlers.onRetry,
        onCancelRetry: handlers.onCancelRetry,
        onCommandAction: handlers.onCommandAction,
        onSend: handlers.onSend,
        onStop: handlers.onStop,
        onEditMessage: handlers.onEditMessage,
        onRegenerateMessage: handlers.onRegenerateMessage,
        onLoadMore: handlers.onLoadMore,
        onRetryHistory: handlers.onRetryHistory,
        'onUpdate:userInput': (value) => { state.userInput = value },
        'onUpdate:model': (value) => { state.model = value },
      })
    },
  })

  app.mount(container)
  patchVirtualScrollLayout(container)

  return {
    container,
    state,
    async repatchLayout() {
      await nextTick()
      patchVirtualScrollLayout(container)
    },
    unmount() {
      app.unmount()
      container.remove()
    },
  }
}

async function flushUi() {
  await nextTick()
  await nextTick()
}

describe('MessageList retry banner', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders retry-after message with countdown progress bar', async () => {
    const view = mountMessageList({
      isRetrying: true,
      statusMessage: '重試中 (2/3)，系統繁忙，將在 3 秒後自動重試...',
      retryCountdown: {
        active: true,
        type: 'rateLimit',
        remainingSec: 3,
        totalSec: 6,
      },
    })
    await flushUi()

    const banner = view.container.querySelector('.retry-countdown-banner')
    expect(banner).toBeTruthy()
    expect(banner.textContent).toContain('重試中 (2/3)，系統繁忙，將在 3 秒後自動重試...')
    const progressFill = view.container.querySelector('.retry-progress-fill')
    expect(progressFill?.getAttribute('style')).toContain('50%')

    view.unmount()
  })

  it('hides retry banner when retrying is disabled', async () => {
    const view = mountMessageList({
      isRetrying: false,
      statusMessage: '重試中 (2/3)，系統繁忙，將在 3 秒後自動重試...',
      retryCountdown: {
        active: true,
        type: 'rateLimit',
        remainingSec: 3,
        totalSec: 6,
      },
    })
    await flushUi()

    expect(view.container.querySelector('.retry-countdown-banner')).toBeNull()

    view.unmount()
  })

  it('shows "AI 正在處理中..." while waiting for first AI token', async () => {
    const view = mountMessageList({
      isProcessing: true,
      messages: [
        { role: 'user', content: '請幫我查服務狀態' },
        { role: 'ai', content: '' },
      ],
    })
    await flushUi()

    expect(view.container.textContent).toContain('AI 正在處理中...')
    view.unmount()
  })

  it('switches typing status text after 10 seconds', async () => {
    vi.useFakeTimers()
    try {
      const view = mountMessageList({
        isProcessing: true,
        messages: [
          { role: 'user', content: '請幫我查服務狀態' },
          { role: 'ai', content: '' },
        ],
      })
      await flushUi()
      expect(view.container.textContent).toContain('AI 正在處理中...')

      await vi.advanceTimersByTimeAsync(10000)
      await flushUi()

      expect(view.container.textContent).toContain('AI 仍在思考，請稍候...')
      view.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows onboarding card and quick slash-command buttons when conversation is empty', async () => {
    const view = mountMessageList({
      messages: [],
      isHistoryLoading: false,
    })
    await flushUi()

    const welcomeCard = view.container.querySelector('.chat-welcome-card')
    expect(welcomeCard).toBeTruthy()
    expect(welcomeCard.textContent).toContain('/status')
    expect(welcomeCard.textContent).toContain('/docker')
    expect(welcomeCard.textContent).toContain('/help')

    view.unmount()
  })

  it('shows history-loading skeleton while conversation history is loading', async () => {
    const view = mountMessageList({
      messages: [],
      isHistoryLoading: true,
      historyLoadFailed: false,
    })
    await flushUi()

    expect(view.container.textContent).toContain('載入對話紀錄中...')
    expect(view.container.querySelector('.chat-welcome-card')).toBeNull()

    view.unmount()
  })

  it('prioritizes history-loading skeleton even when stale messages still exist', async () => {
    const view = mountMessageList({
      messages: [{ role: 'ai', content: '舊對話內容' }],
      isHistoryLoading: true,
      historyLoadFailed: false,
    })
    await flushUi()

    expect(view.container.textContent).toContain('載入對話紀錄中...')
    expect(view.container.textContent).not.toContain('舊對話內容')

    view.unmount()
  })

  it('shows history-load-failed empty state and hides welcome card', async () => {
    const view = mountMessageList({
      messages: [],
      isHistoryLoading: false,
      historyLoadFailed: true,
    })
    await flushUi()

    expect(view.container.textContent).toContain('載入對話失敗')
    expect(view.container.querySelector('.chat-welcome-card')).toBeNull()

    view.unmount()
  })

  it('emits retry-history when clicking retry button in failed empty state', async () => {
    const onRetryHistory = vi.fn()
    const view = mountMessageList(
      {
        messages: [],
        isHistoryLoading: false,
        historyLoadFailed: true,
      },
      { onRetryHistory }
    )
    await flushUi()

    const retryButton = Array.from(view.container.querySelectorAll('button'))
      .find((button) => button.textContent.includes('重試'))
    expect(retryButton).toBeTruthy()

    retryButton.click()
    expect(onRetryHistory).toHaveBeenCalledTimes(1)

    view.unmount()
  })

  it('clicking a quick command fills input and triggers send', async () => {
    const onSend = vi.fn()
    const view = mountMessageList(
      {
        messages: [],
        isHistoryLoading: false,
      },
      { onSend }
    )
    await flushUi()

    const shortcutButtons = Array.from(view.container.querySelectorAll('.welcome-shortcut-btn'))
    const statusButton = shortcutButtons.find((btn) => btn.textContent.includes('/status'))
    expect(statusButton).toBeTruthy()
    statusButton.click()
    await flushUi()

    expect(view.state.userInput).toBe('/status')
    expect(onSend).toHaveBeenCalledTimes(1)

    view.unmount()
  })

  it('clicking expired-command resend fills input without auto-send', async () => {
    const onSend = vi.fn()
    const now = new Date('2026-03-12T12:00:00Z').getTime()
    vi.useFakeTimers()
    vi.setSystemTime(now)

    const view = mountMessageList(
      {
        messages: [
          {
            role: 'ai',
            content: '此操作需要確認',
            command: {
              content: 'rm -rf /tmp/demo',
              status: 'pending',
              timeoutAt: now - 1000,
            },
          },
        ],
      },
      { onSend }
    )
    await flushUi()
    await view.repatchLayout()
    await flushUi()

    const summaryRow = view.container.querySelector('.cmd-summary-row')
    expect(summaryRow).toBeTruthy()
    summaryRow.click()
    await flushUi()

    const resendButton = Array.from(view.container.querySelectorAll('button'))
      .find((button) => button.textContent.includes('重新發送此指令'))
    expect(resendButton).toBeTruthy()
    resendButton.click()
    await flushUi()

    expect(view.state.userInput).toBe('rm -rf /tmp/demo')
    expect(onSend).not.toHaveBeenCalled()

    view.unmount()
    vi.useRealTimers()
  })

  it('shows edit action for user messages and emits absolute index', async () => {
    const onEditMessage = vi.fn()
    const view = mountMessageList(
      {
        messages: [{ role: 'user', content: '請幫我查 CPU 使用率' }],
      },
      { onEditMessage }
    )
    await flushUi()
    await view.repatchLayout()
    await flushUi()

    const editButton = view.container.querySelector('button[aria-label="編輯訊息"]')
    expect(editButton).toBeTruthy()
    editButton.click()

    expect(onEditMessage).toHaveBeenCalledTimes(1)
    expect(onEditMessage).toHaveBeenCalledWith(0)
    view.unmount()
  })

  it('shows regenerate action for ai messages and emits absolute index', async () => {
    const onRegenerateMessage = vi.fn()
    const view = mountMessageList(
      {
        messages: [
          { role: 'user', content: '請列出磁碟空間' },
          { role: 'ai', content: '目前磁碟空間如下...' },
        ],
        canRegenerateMessage: (index) => index === 1,
      },
      { onRegenerateMessage }
    )
    await flushUi()
    await view.repatchLayout()
    await flushUi()

    const regenerateButton = view.container.querySelector('button[aria-label="重新生成回覆"]')
    expect(regenerateButton).toBeTruthy()
    regenerateButton.click()

    expect(onRegenerateMessage).toHaveBeenCalledTimes(1)
    expect(onRegenerateMessage).toHaveBeenCalledWith(1)
    view.unmount()
  })
})
