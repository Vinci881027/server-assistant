import { createApp, h, nextTick, reactive } from 'vue'
import ControlPanel from '../ControlPanel.vue'

function mountControlPanel(initialState = {}) {
  const state = reactive({
    model: '120b',
    userInput: '',
    isProcessing: false,
    statusMessage: '',
    availableModels: {
      '120b': {
        label: 'GPT OSS 120B',
        category: 'High Intelligence',
        available: false,
        suggestAlternative: '20b',
      },
      '20b': {
        label: 'GPT OSS 20B',
        category: 'High Intelligence',
        available: true,
      },
    },
    isAdmin: false,
    isOnline: true,
    ...initialState,
  })

  const container = document.createElement('div')
  document.body.appendChild(container)

  const app = createApp({
    render() {
      return h(ControlPanel, {
        model: state.model,
        userInput: state.userInput,
        isProcessing: state.isProcessing,
        statusMessage: state.statusMessage,
        availableModels: state.availableModels,
        isAdmin: state.isAdmin,
        isOnline: state.isOnline,
        'onUpdate:model': (value) => { state.model = value },
        'onUpdate:userInput': (value) => { state.userInput = value },
        onSend: () => {},
        onStop: () => {},
        onComposerResize: () => {},
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

describe('ControlPanel model availability', () => {
  afterEach(() => {
    document.body.innerHTML = ''
  })

  it('shows overload warning for unavailable selected model', async () => {
    const view = mountControlPanel()
    await flushUi()

    expect(view.container.textContent).toContain('⚠️ 目前負載高')
    expect(view.container.textContent).toContain('建議切換至 GPT OSS 20B')

    view.unmount()
  })

  it('switches to suggested alternative model when suggestion button clicked', async () => {
    const view = mountControlPanel()
    await flushUi()

    const suggestButton = Array.from(view.container.querySelectorAll('button'))
      .find(btn => btn.textContent?.includes('建議切換至'))

    expect(suggestButton).toBeTruthy()
    suggestButton.click()
    await flushUi()

    expect(view.state.model).toBe('20b')

    view.unmount()
  })
})
