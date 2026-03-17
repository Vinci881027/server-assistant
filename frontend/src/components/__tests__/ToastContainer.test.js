import { createApp, h, nextTick, reactive } from 'vue'
import ToastContainer from '../ToastContainer.vue'

function mountToastContainer(initialToasts = []) {
  const state = reactive({
    toasts: [...initialToasts],
  })

  const onAction = vi.fn()
  const onDismiss = vi.fn()

  const container = document.createElement('div')
  document.body.appendChild(container)

  const app = createApp({
    render() {
      return h(ToastContainer, {
        toasts: state.toasts,
        onAction,
        onDismiss,
      })
    },
  })

  app.mount(container)

  return {
    container,
    state,
    onAction,
    onDismiss,
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

describe('ToastContainer', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('renders info and undo toasts and emits action with toast id', async () => {
    const view = mountToastContainer([
      { id: 'info-1', type: 'info', message: '模型已切換', duration: 3000 },
      { id: 'undo-1', type: 'undo', message: '已刪除對話', duration: 5000 },
    ])
    await flushUi()

    expect(view.container.textContent).toContain('模型已切換')
    expect(view.container.textContent).toContain('已刪除對話')

    const undoButton = Array.from(view.container.querySelectorAll('button'))
      .find((btn) => btn.textContent?.includes('復原'))
    expect(undoButton).toBeTruthy()
    undoButton.click()

    expect(view.onAction).toHaveBeenCalledTimes(1)
    expect(view.onAction).toHaveBeenCalledWith('undo-1')

    const countdown = view.container.querySelector('.toast-countdown')
    expect(countdown?.getAttribute('style')).toContain('animation-duration: 5000ms')

    view.unmount()
  })

  it('updates rendered toast queue when toasts are appended reactively', async () => {
    const view = mountToastContainer()
    await flushUi()
    expect(view.container.textContent).not.toContain('Queue A')

    view.state.toasts.push(
      { id: 'info-a', type: 'info', message: 'Queue A', duration: 3000 },
      { id: 'info-b', type: 'info', message: 'Queue B', duration: 3000 }
    )
    await flushUi()

    expect(view.container.textContent).toContain('Queue A')
    expect(view.container.textContent).toContain('Queue B')
    expect(view.container.querySelectorAll('.pointer-events-auto').length).toBe(2)

    view.unmount()
  })
})
