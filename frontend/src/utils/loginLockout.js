/**
 * Formats lockout duration as whole minutes for login UX messaging.
 *
 * @param {number|string|null|undefined} retryAfterSeconds
 * @returns {string|null}
 */
export function formatLoginLockoutMessage(retryAfterSeconds) {
  const parsedSeconds = Number(retryAfterSeconds)
  if (!Number.isFinite(parsedSeconds) || parsedSeconds <= 0) {
    return null
  }

  const minutes = Math.max(1, Math.ceil(parsedSeconds / 60))
  return `帳號已鎖定，${minutes} 分鐘後再試`
}

/**
 * Number of remaining attempts at which to show the near-lockout warning.
 */
export const LOCKOUT_WARNING_THRESHOLD = 2

/**
 * Returns a warning message when the user is close to being locked out.
 *
 * @param {number|string|null|undefined} remainingAttempts
 * @returns {string|null}
 */
export function formatLockoutWarning(remainingAttempts) {
  const n = Number(remainingAttempts)
  if (!Number.isFinite(n) || n <= 0 || n > LOCKOUT_WARNING_THRESHOLD) return null
  return `再失敗 ${n} 次將鎖定帳號 15 分鐘`
}

/**
 * Formats a remaining lockout duration in MM:SS for live countdown display.
 *
 * @param {number} seconds
 * @returns {string}
 */
export function formatLockoutCountdown(seconds) {
  const s = Math.max(0, Math.floor(Number(seconds)))
  const m = Math.floor(s / 60)
  const sec = s % 60
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}
