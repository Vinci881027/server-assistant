<template>
  <div class="login-wrapper relative flex min-h-screen items-center justify-center px-6 py-12 overflow-hidden">
    <!-- Animated gradient background -->
    <div class="absolute inset-0 login-bg"></div>
    <!-- Decorative blurred orbs -->
    <div class="absolute top-1/4 -left-20 w-72 h-72 rounded-full blur-3xl opacity-20" style="background-color: var(--accent-primary);"></div>
    <div class="absolute bottom-1/4 -right-20 w-80 h-80 rounded-full blur-3xl opacity-15" style="background-color: var(--accent-secondary);"></div>

    <!-- Theme toggle -->
    <button
      @click="themeStore.toggleTheme()"
      class="absolute top-6 right-6 p-2.5 rounded-xl border backdrop-blur-sm transition-all hover:scale-110 z-10"
      style="background-color: var(--glass-bg); border-color: var(--glass-border);"
      title="切換主題"
      :aria-label="themeStore.isDark ? '切換為淺色主題' : '切換為深色主題'"
    >
      <!-- Sun icon (shown in dark mode) -->
      <svg v-if="themeStore.isDark" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--accent-warning);">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z" />
      </svg>
      <!-- Moon icon (shown in light mode) -->
      <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--accent-primary);">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" />
      </svg>
    </button>

    <!-- Login card -->
    <div
      class="relative z-10 w-full max-w-md transition-all duration-700 ease-out"
      :class="isMounted ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'"
    >
      <div class="login-card rounded-2xl p-8 shadow-2xl backdrop-blur-xl border"
           style="background-color: var(--glass-bg); border-color: var(--glass-border);">

        <!-- Logo & Title -->
        <div class="text-center mb-8">
          <div class="inline-flex items-center justify-center w-16 h-16 rounded-2xl mb-4 shadow-lg"
               style="background: linear-gradient(135deg, var(--accent-primary), var(--accent-secondary));">
            <!-- Reuse the same SVG as favicon -->
            <span class="login-favicon-icon" v-html="faviconSvg" aria-hidden="true"></span>
          </div>
          <h1 class="text-2xl font-bold tracking-tight" style="color: var(--text-primary);">
            CGV Lab Server Assistant
          </h1>
          <p class="mt-2 text-sm" style="color: var(--text-tertiary);">
            請輸入伺服器系統使用者帳號與密碼
          </p>
        </div>

        <!-- Form -->
        <form class="space-y-5" @submit.prevent="handleLogin">
          <!-- Username -->
          <div>
            <label for="username" class="block text-sm font-medium mb-2" style="color: var(--text-secondary);">
              使用者名稱
            </label>
            <div class="relative">
              <div class="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
                </svg>
              </div>
              <input
                id="username"
                name="username"
                type="text"
                v-model="username"
                autofocus
                required
                class="block w-full rounded-xl border py-3 pl-11 pr-4 text-sm outline-none transition-all duration-200 placeholder:opacity-50"
                style="background-color: var(--bg-input); color: var(--text-primary); border-color: var(--border-primary);"
                @focus="$event.target.style.borderColor = 'var(--accent-primary)'"
                @blur="$event.target.style.borderColor = 'var(--border-primary)'"
              />
            </div>
          </div>

          <!-- Password -->
          <div>
            <label for="password" class="block text-sm font-medium mb-2" style="color: var(--text-secondary);">
              密碼
            </label>
            <div class="relative">
              <div class="absolute inset-y-0 left-0 pl-3.5 flex items-center pointer-events-none">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" style="color: var(--text-tertiary);">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
                </svg>
              </div>
              <input
                id="password"
                name="password"
                :type="showPassword ? 'text' : 'password'"
                v-model="password"
                required
                class="block w-full rounded-xl border py-3 pl-11 pr-12 text-sm outline-none transition-all duration-200 placeholder:opacity-50"
                style="background-color: var(--bg-input); color: var(--text-primary); border-color: var(--border-primary);"
                @focus="$event.target.style.borderColor = 'var(--accent-primary)'"
                @blur="$event.target.style.borderColor = 'var(--border-primary)'"
              />
              <button
                type="button"
                class="absolute inset-y-0 right-0 px-3 flex items-center rounded-r-xl transition-colors hover:opacity-80"
                style="color: var(--text-tertiary);"
                :aria-label="showPassword ? '隱藏密碼' : '顯示密碼'"
                :title="showPassword ? '隱藏密碼' : '顯示密碼'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <!-- Eye open (hide password) -->
                <svg v-if="showPassword" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.477 0 8.268 2.943 9.542 7-1.274 4.057-5.065 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" />
                </svg>
                <!-- Eye slash (show password) -->
                <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M13.875 18.825A10.05 10.05 0 0112 19c-4.477 0-8.268-2.943-9.542-7a9.97 9.97 0 012.065-3.633M6.64 6.635A9.953 9.953 0 0112 5c4.477 0 8.268 2.943 9.542 7a9.97 9.97 0 01-1.88 3.37M15 12a3 3 0 11-6 0 3 3 0 016 0zm-6.364-6.364L3 3m18 18l-3.636-3.636" />
                </svg>
              </button>
            </div>
          </div>

          <!-- Submit button -->
          <button
            type="submit"
            :disabled="isLoading"
            class="login-btn flex w-full justify-center items-center rounded-xl py-3 text-sm font-semibold text-white shadow-lg transition-all duration-200 hover:-translate-y-0.5 hover:shadow-xl disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:translate-y-0"
          >
            <svg v-if="isLoading" class="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
            {{ isLoading ? '驗證中...' : '登入' }}
          </button>

          <!-- Message -->
          <div v-if="message" class="text-center text-sm font-medium" :style="{ color: isError ? 'var(--accent-danger)' : 'var(--accent-success)' }">
            {{ message }}
          </div>
        </form>
      </div>

      <!-- Footer -->
      <p class="text-center text-xs mt-6" style="color: var(--text-tertiary);">
        Server Assistant v1.0
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useThemeStore } from '../stores/themeStore'
import { useAuthStore } from '../stores/authStore'
import faviconSvg from '../assets/favicon.svg?raw'

const themeStore = useThemeStore()
const authStore = useAuthStore()

const emit = defineEmits(['login-success'])
const username = ref('')
const password = ref('')
const isLoading = ref(false)
const message = ref('')
const isError = ref(false)
const isMounted = ref(false)
const showPassword = ref(false)

onMounted(() => {
  requestAnimationFrame(() => isMounted.value = true)
})

const formatLockoutMessage = (retryAfterSeconds) => {
  const parsedSeconds = Number(retryAfterSeconds)
  if (!Number.isFinite(parsedSeconds) || parsedSeconds <= 0) {
    return null
  }
  const retryAfterMinutes = Math.max(1, Math.ceil(parsedSeconds / 60))
  return `帳號已鎖定，請 ${retryAfterMinutes} 分鐘後再試`
}

const handleLogin = async () => {
  isLoading.value = true
  message.value = ''
  isError.value = false

  try {
    const result = await authStore.login(username.value, password.value)

    if (result.success) {
      message.value = result.message || '驗證成功！正在初始化使用者環境...'
      emit('login-success')
    } else {
      isError.value = true
      if (result.code === 'LOGIN_RATE_LIMITED') {
        const lockoutMessage = formatLockoutMessage(result.data?.retryAfterSeconds)
        if (lockoutMessage) {
          message.value = lockoutMessage
          return
        }
      }
      message.value = result.message || result.error?.message || '登入失敗：使用者名稱不存在或密碼錯誤'
    }
  } catch (error) {
    isError.value = true
    message.value = '連線錯誤：無法連接到後端伺服器'
    console.error('Login error:', error)
  } finally {
    isLoading.value = false
  }
}
</script>

<style scoped>
.login-wrapper {
  background-color: var(--bg-primary);
}

.login-favicon-icon {
  color: #fff;
}
.login-favicon-icon :deep(svg) {
  width: 2rem;
  height: 2rem;
  display: block;
}

.login-bg {
  background: radial-gradient(ellipse at 30% 20%, color-mix(in srgb, var(--accent-primary) 8%, transparent), transparent 60%),
              radial-gradient(ellipse at 70% 80%, color-mix(in srgb, var(--accent-secondary) 6%, transparent), transparent 60%);
}

.login-btn {
  background: linear-gradient(135deg, var(--accent-primary), color-mix(in srgb, var(--accent-primary) 70%, #a855f7));
}

.login-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, var(--accent-primary-hover), color-mix(in srgb, var(--accent-primary-hover) 70%, #a855f7));
}
</style>
