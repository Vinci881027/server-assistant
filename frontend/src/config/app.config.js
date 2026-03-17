/**
 * Application Configuration
 *
 * Environment-specific configuration values
 */

// Determine if running in development mode
const isDevelopment = Boolean(import.meta.env?.DEV)
const DEFAULT_APP_NAME = 'CGV Lab Server Assistant'
const resolvedAppName = typeof import.meta.env?.VITE_APP_NAME === 'string'
  ? import.meta.env.VITE_APP_NAME.trim()
  : ''

// API Base URL (handled by Vite proxy in dev mode, same path in production)
export const API_BASE_URL = '/api'

// Application Metadata
export const APP_CONFIG = {
  name: resolvedAppName || DEFAULT_APP_NAME,
}

// Feature Flags
export const FEATURES = {
  enableAdmin: true,
  enableMemory: true,
  enableMultiModel: true,
  enableConversationHistory: true,
  enableMarkdownRendering: true,
}

// UI Configuration
export const UI_CONFIG = {
  theme: 'dark',
  sidebarDefaultOpen: true,
  messageAnimationDuration: 300,
  scrollBehavior: 'smooth',
  messageWindowSize: 80,
  messageWindowLoadChunk: 20,
  markdownMaxRenderLength: 20000,
  virtualScrollOverscan: 5,
  virtualScrollEstimateSize: 120,
}

// Chat Configuration
export const CHAT_CONFIG = {
  maxMessageLength: 8000,
  // Backend context window; frontend history pagination/rendering is managed separately.
  maxMessagesPerConversation: 20,
  historyPageSize: 50,
  streamingEnabled: true,
  retryAttempts: 3,
  retryDelay: 1000,
}

// Command Confirmation Configuration
const DEFAULT_COMMAND_CONFIRM_TIMEOUT_SECONDS = 120
export const COMMAND_CONFIG = {
  confirmTimeoutSeconds: DEFAULT_COMMAND_CONFIRM_TIMEOUT_SECONDS,
}
export const COMMAND_CONFIRM_TIMEOUT_SECONDS = Number.isInteger(COMMAND_CONFIG.confirmTimeoutSeconds)
  && COMMAND_CONFIG.confirmTimeoutSeconds > 0
  ? COMMAND_CONFIG.confirmTimeoutSeconds
  : DEFAULT_COMMAND_CONFIRM_TIMEOUT_SECONDS

// Debug Mode
export const DEBUG = {
  enabled: isDevelopment,
  logApiCalls: isDevelopment,
  logStateChanges: isDevelopment,
}

export default {
  APP_CONFIG,
  API_BASE_URL,
  FEATURES,
  UI_CONFIG,
  CHAT_CONFIG,
  COMMAND_CONFIG,
  DEBUG,
}
