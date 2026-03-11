/**
 * Application Constants
 *
 * Centralized constants to eliminate magic numbers and strings
 */

// ========== Message Roles ==========
export const MESSAGE_ROLE = {
  USER: 'user',
  ASSISTANT: 'assistant',
  SYSTEM: 'system',
}

// ========== Status Messages ==========
export const STATUS = {
  SUMMARIZING: '[STATUS:SUMMARIZING]',
  LOADING: 'Loading...',
  ERROR: 'Error',
  SUCCESS: 'Success',
}

// ========== API Timeouts ==========
export const TIMEOUT = {
  DEFAULT: 30000, // 30 seconds
  LONG: 60000,    // 60 seconds
  SHORT: 5000,    // 5 seconds
}

// ========== UI Constants ==========
export const UI = {
  SIDEBAR_WIDTH: 256,
  HEADER_HEIGHT: 64,
  MAX_MESSAGE_LENGTH: 8000,
  SCROLL_THRESHOLD: 100,
}

// ========== Model Keys ==========
export const MODEL_KEY = {
  GPT_OSS_120B: '120b',
  GPT_OSS_20B: '20b',
}

// ========== Default Values ==========
export const DEFAULTS = {
  MODEL: MODEL_KEY.GPT_OSS_20B,
  USE_MEMORY: false,
  SERVER_IP: 'Loading...',
}

// ========== Error Messages ==========
export const ERROR_MESSAGE = {
  NETWORK: '連線錯誤：無法連接到後端伺服器',
  UNAUTHORIZED: '未授權：請重新登入',
  FORBIDDEN: '權限不足：無法執行此操作',
  NOT_FOUND: '資源不存在',
  SERVER_ERROR: '伺服器內部錯誤',
  UNKNOWN: '未知錯誤',
}

// ========== Success Messages ==========
export const SUCCESS_MESSAGE = {
  LOGIN: '登入成功',
  LOGOUT: '登出成功',
  SAVE: '儲存成功',
  DELETE: '刪除成功',
  CLEAR: '清除成功',
}
