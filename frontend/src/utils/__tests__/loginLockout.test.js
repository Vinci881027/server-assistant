import {
  formatLoginLockoutMessage,
  formatLockoutWarning,
  formatLockoutCountdown,
  LOCKOUT_WARNING_THRESHOLD,
} from '../loginLockout'

describe('formatLoginLockoutMessage', () => {
  it('formats lockout duration as whole minutes', () => {
    expect(formatLoginLockoutMessage(225)).toBe('帳號已鎖定，4 分鐘後再試')
  })

  it('rounds up to at least 1 minute', () => {
    expect(formatLoginLockoutMessage(61)).toBe('帳號已鎖定，2 分鐘後再試')
    expect(formatLoginLockoutMessage(1)).toBe('帳號已鎖定，1 分鐘後再試')
  })

  it('returns null for invalid lockout duration', () => {
    expect(formatLoginLockoutMessage(0)).toBeNull()
    expect(formatLoginLockoutMessage(-10)).toBeNull()
    expect(formatLoginLockoutMessage('not-a-number')).toBeNull()
  })
})

describe('formatLockoutWarning', () => {
  it('returns warning message when at or below threshold', () => {
    expect(formatLockoutWarning(1)).toBe('再失敗 1 次將鎖定帳號 15 分鐘')
    expect(formatLockoutWarning(LOCKOUT_WARNING_THRESHOLD)).toBe(`再失敗 ${LOCKOUT_WARNING_THRESHOLD} 次將鎖定帳號 15 分鐘`)
  })

  it('returns null when above threshold', () => {
    expect(formatLockoutWarning(LOCKOUT_WARNING_THRESHOLD + 1)).toBeNull()
    expect(formatLockoutWarning(10)).toBeNull()
  })

  it('returns null for invalid input', () => {
    expect(formatLockoutWarning(0)).toBeNull()
    expect(formatLockoutWarning(-1)).toBeNull()
    expect(formatLockoutWarning(null)).toBeNull()
    expect(formatLockoutWarning('not-a-number')).toBeNull()
  })
})

describe('formatLockoutCountdown', () => {
  it('formats seconds as MM:SS', () => {
    expect(formatLockoutCountdown(90)).toBe('01:30')
    expect(formatLockoutCountdown(61)).toBe('01:01')
    expect(formatLockoutCountdown(59)).toBe('00:59')
  })

  it('zero-pads single-digit values', () => {
    expect(formatLockoutCountdown(5)).toBe('00:05')
    expect(formatLockoutCountdown(65)).toBe('01:05')
  })

  it('clamps to 00:00 for non-positive values', () => {
    expect(formatLockoutCountdown(0)).toBe('00:00')
    expect(formatLockoutCountdown(-10)).toBe('00:00')
  })
})
