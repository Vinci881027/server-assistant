import { createApp, h, nextTick } from 'vue'
import { createPinia } from 'pinia'
import Login from '../Login.vue'
import { useAuthStore } from '../../stores/authStore'

const originalRequestAnimationFrame = globalThis.requestAnimationFrame

beforeAll(() => {
  if (typeof globalThis.requestAnimationFrame !== 'function') {
    globalThis.requestAnimationFrame = (callback) => setTimeout(callback, 0)
  }
})

afterAll(() => {
  if (typeof originalRequestAnimationFrame === 'undefined') {
    delete globalThis.requestAnimationFrame
    return
  }
  globalThis.requestAnimationFrame = originalRequestAnimationFrame
})

function mountLogin() {
  const onLoginSuccess = vi.fn()
  const container = document.createElement('div')
  document.body.appendChild(container)

  const pinia = createPinia()
  const app = createApp({
    render() {
      return h(Login, {
        onLoginSuccess,
      })
    },
  })

  app.use(pinia)
  app.mount(container)

  const authStore = useAuthStore(pinia)
  authStore.login = vi.fn()

  return {
    container,
    authStore,
    onLoginSuccess,
    unmount() {
      app.unmount()
      container.remove()
    },
  }
}

async function flushUi() {
  await Promise.resolve()
  await nextTick()
  await nextTick()
}

function fillCredentials(container, username, password) {
  const usernameInput = container.querySelector('#username')
  const passwordInput = container.querySelector('#password')

  usernameInput.value = username
  usernameInput.dispatchEvent(new Event('input', { bubbles: true }))
  passwordInput.value = password
  passwordInput.dispatchEvent(new Event('input', { bubbles: true }))
}

async function submitLoginForm(container) {
  const form = container.querySelector('form')
  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushUi()
}

describe('Login', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('submits credentials and emits login-success when login succeeds', async () => {
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: true,
      message: '驗證成功！正在初始化使用者環境...',
    })

    fillCredentials(view.container, 'alice', 'secret')
    await submitLoginForm(view.container)

    expect(view.authStore.login).toHaveBeenCalledWith('alice', 'secret')
    expect(view.onLoginSuccess).toHaveBeenCalledTimes(1)
    expect(view.container.textContent).toContain('驗證成功！正在初始化使用者環境...')

    view.unmount()
  })

  it('shows countdown and disables button when backend rate-limits login attempts', async () => {
    vi.useFakeTimers()
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      code: 'LOGIN_RATE_LIMITED',
      data: { retryAfterSeconds: 61 },
      message: 'Too many attempts',
    })

    fillCredentials(view.container, 'alice', 'wrong-password')
    await submitLoginForm(view.container)

    expect(view.onLoginSuccess).not.toHaveBeenCalled()
    expect(view.container.textContent).toContain('帳號已鎖定，請等待')
    expect(view.container.textContent).toContain('01:01')
    expect(view.container.querySelector('button[type="submit"]').disabled).toBe(true)
    expect(view.container.querySelector('button[type="submit"]').textContent.trim()).toBe('帳號已鎖定')

    view.unmount()
  })

  it('falls back to 60-second countdown when retry-after is missing', async () => {
    vi.useFakeTimers()
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      code: 'LOGIN_RATE_LIMITED',
      data: {},
      message: 'Too many attempts',
    })

    fillCredentials(view.container, 'alice', 'wrong-password')
    await submitLoginForm(view.container)

    expect(view.onLoginSuccess).not.toHaveBeenCalled()
    expect(view.container.textContent).toContain('帳號已鎖定，請等待')
    expect(view.container.querySelector('button[type="submit"]').disabled).toBe(true)

    view.unmount()
  })

  it('shows near-lockout warning when 1 attempt remains', async () => {
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      code: 'AUTH_FAILED',
      data: { remainingAttempts: 1 },
      message: '登入失敗：使用者名稱或密碼錯誤',
    })

    fillCredentials(view.container, 'alice', 'wrong-password')
    await submitLoginForm(view.container)

    expect(view.onLoginSuccess).not.toHaveBeenCalled()
    expect(view.container.textContent).toContain('再失敗 1 次將鎖定帳號 15 分鐘')
    expect(view.container.querySelector('button[type="submit"]').disabled).toBe(false)

    view.unmount()
  })

  it('shows near-lockout warning when 2 attempts remain', async () => {
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      code: 'AUTH_FAILED',
      data: { remainingAttempts: 2 },
      message: '登入失敗：使用者名稱或密碼錯誤',
    })

    fillCredentials(view.container, 'alice', 'wrong-password')
    await submitLoginForm(view.container)

    expect(view.container.textContent).toContain('再失敗 2 次將鎖定帳號 15 分鐘')

    view.unmount()
  })

  it('does not show near-lockout warning when many attempts remain', async () => {
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      code: 'AUTH_FAILED',
      data: { remainingAttempts: 5 },
      message: '登入失敗：使用者名稱或密碼錯誤',
    })

    fillCredentials(view.container, 'alice', 'wrong-password')
    await submitLoginForm(view.container)

    expect(view.container.textContent).not.toContain('將鎖定帳號')

    view.unmount()
  })

  it('shows invalid-credential message for authentication failures', async () => {
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      code: 'AUTH_FAILED',
      message: '登入失敗：使用者名稱或密碼錯誤',
    })

    fillCredentials(view.container, 'alice', 'wrong-password')
    await submitLoginForm(view.container)

    expect(view.onLoginSuccess).not.toHaveBeenCalled()
    expect(view.container.textContent).toContain('密碼或帳號錯誤')

    view.unmount()
  })

  it('shows connection error message when login fails due to network', async () => {
    const view = mountLogin()
    view.authStore.login.mockResolvedValue({
      success: false,
      message: 'Network Error - Please check your connection',
      transportCode: 'ERR_NETWORK',
    })

    fillCredentials(view.container, 'alice', 'secret')
    await submitLoginForm(view.container)

    expect(view.onLoginSuccess).not.toHaveBeenCalled()
    expect(view.container.textContent).toContain('無法連線伺服器，請確認網路')

    view.unmount()
  })
})
