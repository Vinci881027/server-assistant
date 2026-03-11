import { createApp, h, nextTick, reactive } from 'vue'
import MessageList from '../MessageList.vue'

const originalResizeObserver = globalThis.ResizeObserver

beforeAll(() => {
  if (typeof globalThis.ResizeObserver === 'undefined') {
    globalThis.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
})

afterAll(() => {
  if (typeof originalResizeObserver === 'undefined') {
    delete globalThis.ResizeObserver
    return
  }
  globalThis.ResizeObserver = originalResizeObserver
})

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
        isLoadingMore: state.isLoadingMore,
        onRetry: handlers.onRetry,
        onCancelRetry: handlers.onCancelRetry,
        onCommandAction: handlers.onCommandAction,
        onSend: handlers.onSend,
        onStop: handlers.onStop,
        onEditMessage: handlers.onEditMessage,
        onRegenerateMessage: handlers.onRegenerateMessage,
        onLoadMore: handlers.onLoadMore,
        'onUpdate:userInput': (value) => { state.userInput = value },
        'onUpdate:model': (value) => { state.model = value },
      })
    },
  })

  app.mount(container)

  return {
    container,
    state,
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
      statusMessage: '系統繁忙，將在 3 秒後自動重試...',
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
    expect(banner.textContent).toContain('系統繁忙，將在 3 秒後自動重試...')
    const progressFill = view.container.querySelector('.retry-progress-fill')
    expect(progressFill?.getAttribute('style')).toContain('50%')

    view.unmount()
  })

  it('hides retry banner when retrying is disabled', async () => {
    const view = mountMessageList({
      isRetrying: false,
      statusMessage: '系統繁忙，將在 3 秒後自動重試...',
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

  it('shows edit action for user messages and emits absolute index', async () => {
    const onEditMessage = vi.fn()
    const view = mountMessageList(
      {
        messages: [{ role: 'user', content: '請幫我查 CPU 使用率' }],
      },
      { onEditMessage }
    )
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

    const regenerateButton = view.container.querySelector('button[aria-label="重新生成回覆"]')
    expect(regenerateButton).toBeTruthy()
    regenerateButton.click()

    expect(onRegenerateMessage).toHaveBeenCalledTimes(1)
    expect(onRegenerateMessage).toHaveBeenCalledWith(1)
    view.unmount()
  })
})
