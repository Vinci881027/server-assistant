import { createApp, h, nextTick, reactive } from 'vue'
import Sidebar from '../Sidebar.vue'

const originalMatchMedia = window.matchMedia

beforeAll(() => {
  if (typeof window.matchMedia !== 'function') {
    window.matchMedia = () => ({
      matches: false,
      media: '',
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    })
  }
})

afterAll(() => {
  if (typeof originalMatchMedia === 'undefined') {
    delete window.matchMedia
    return
  }
  window.matchMedia = originalMatchMedia
})

function mountSidebar(initialProps = {}) {
  const props = reactive({
    isOpen: true,
    conversations: [],
    currentId: '',
    ...initialProps,
  })

  const container = document.createElement('div')
  document.body.appendChild(container)

  const app = createApp({
    render() {
      return h(Sidebar, {
        isOpen: props.isOpen,
        conversations: props.conversations,
        currentId: props.currentId,
      })
    },
  })

  app.mount(container)

  return {
    container,
    props,
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

describe('Sidebar search debounce', () => {
  afterEach(() => {
    vi.useRealTimers()
    localStorage.clear()
    document.body.innerHTML = ''
  })

  it('shows spinner while debouncing and applies filtering after 300ms', async () => {
    vi.useFakeTimers()

    const view = mountSidebar({
      conversations: [
        { id: '1', title: 'Alpha chat', updatedAt: '2026-03-17T10:00:00Z', messageCount: 3 },
        { id: '2', title: 'Beta chat', updatedAt: '2026-03-16T10:00:00Z', messageCount: 2 },
      ],
    })

    await flushUi()

    const input = view.container.querySelector('input[placeholder="搜尋對話..."]')
    expect(input).toBeTruthy()
    input.value = 'alpha'
    input.dispatchEvent(new Event('input'))
    await flushUi()

    expect(view.container.querySelector('.animate-spin')).toBeTruthy()
    expect(view.container.textContent).toContain('Beta chat')

    vi.advanceTimersByTime(300)
    await flushUi()

    expect(view.container.querySelector('.animate-spin')).toBeNull()
    expect(view.container.textContent).toContain('找到 1 個')
    expect(view.container.textContent).toContain('Alpha chat')
    expect(view.container.textContent).not.toContain('Beta chat')

    view.unmount()
  })
})
