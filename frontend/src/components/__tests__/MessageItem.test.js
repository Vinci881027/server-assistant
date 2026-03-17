import { createApp, h, nextTick, reactive } from 'vue'
import MessageItem from '../MessageItem.vue'
import { useMarkdownRenderer } from '../../composables/useMarkdownRenderer'

function mountMessageItem(initialProps = {}, handlerOverrides = {}) {
  const props = reactive({
    entry: {
      msg: {
        role: 'ai',
        content: 'hello from assistant',
      },
      messageKey: 'msg-1',
      absoluteIndex: 3,
    },
    isProcessing: false,
    isTouchDevice: false,
    canRegenerateMessage: null,
    collapsedLines: 18,
    isExpanded: false,
    renderedHtml: '<p>hello from assistant</p>',
    shouldCollapse: false,
    ...initialProps,
  })

  const handlers = {
    onCommandAction: vi.fn(),
    onEditMessage: vi.fn(),
    onRegenerateMessage: vi.fn(),
    onToggleExpand: vi.fn(),
    onTouchStart: vi.fn(),
    onTouchMove: vi.fn(),
    onTouchEnd: vi.fn(),
    onCopy: vi.fn(),
    ...handlerOverrides,
  }

  const container = document.createElement('div')
  document.body.appendChild(container)

  const app = createApp({
    render() {
      return h(MessageItem, {
        entry: props.entry,
        isProcessing: props.isProcessing,
        isTouchDevice: props.isTouchDevice,
        canRegenerateMessage: props.canRegenerateMessage,
        collapsedLines: props.collapsedLines,
        isExpanded: props.isExpanded,
        renderedHtml: props.renderedHtml,
        shouldCollapse: props.shouldCollapse,
        onCommandAction: handlers.onCommandAction,
        onEditMessage: handlers.onEditMessage,
        onRegenerateMessage: handlers.onRegenerateMessage,
        onToggleExpand: handlers.onToggleExpand,
        onTouchStart: handlers.onTouchStart,
        onTouchMove: handlers.onTouchMove,
        onTouchEnd: handlers.onTouchEnd,
        onCopy: handlers.onCopy,
      })
    },
  })

  app.mount(container)

  return {
    container,
    props,
    handlers,
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

describe('MessageItem', () => {
  afterEach(() => {
    delete globalThis.__xssProbe
    document.body.innerHTML = ''
  })

  it('renders sanitized markdown without executable XSS payload', async () => {
    globalThis.__xssProbe = 0
    const { renderMarkdown } = useMarkdownRenderer()
    const sanitizedHtml = renderMarkdown('Hi <img src="x" onerror="window.__xssProbe=1"><script>window.__xssProbe=2</script>')

    expect(sanitizedHtml).toContain('&lt;img')
    expect(sanitizedHtml).not.toContain('<img')
    expect(sanitizedHtml).not.toContain('<script>')

    const view = mountMessageItem({
      renderedHtml: sanitizedHtml,
    })
    await flushUi()

    expect(view.container.querySelector('script')).toBeNull()
    expect(view.container.querySelector('.markdown-content img')).toBeNull()
    expect(globalThis.__xssProbe).toBe(0)

    view.unmount()
  })

  it('emits regenerate-message with absolute index for AI message action', async () => {
    const onRegenerateMessage = vi.fn()
    const view = mountMessageItem(
      {
        entry: {
          msg: { role: 'ai', content: 'first answer' },
          messageKey: 'msg-2',
          absoluteIndex: 12,
        },
        canRegenerateMessage: () => true,
      },
      { onRegenerateMessage }
    )
    await flushUi()

    const regenerateButton = Array.from(view.container.querySelectorAll('button'))
      .find((btn) => btn.getAttribute('aria-label') === '重新生成回覆')
    expect(regenerateButton).toBeTruthy()

    regenerateButton.click()
    await flushUi()

    expect(onRegenerateMessage).toHaveBeenCalledTimes(1)
    expect(onRegenerateMessage).toHaveBeenCalledWith(12)

    view.unmount()
  })

  it('hides desktop copy button on touch devices', async () => {
    const view = mountMessageItem({
      isTouchDevice: true,
    })
    await flushUi()

    const copyButton = view.container.querySelector('button[aria-label="複製訊息內容"]')
    expect(copyButton).toBeNull()

    view.unmount()
  })
})
