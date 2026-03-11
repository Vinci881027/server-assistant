import { describe, expect, it } from 'vitest'
import {
  extractCommandMarker,
  extractRateLimitMarker,
  extractResolvedCommandMarker,
  extractResolvedCommandText,
  hydrateMessageWithCommand,
} from '../commandMarkers.js'

describe('commandMarkers', () => {
  it('extracts pending command marker from assistant content', () => {
    const marker = extractCommandMarker('請確認 [CMD:::rm -rf /tmp/demo:::]')

    expect(marker).toEqual({
      command: 'rm -rf /tmp/demo',
      cleanedContent: '請確認 ',
    })
  })

  it('hydrates cancelled command text into resolved command card payload', () => {
    const hydrated = hydrateMessageWithCommand({
      role: 'ai',
      content: '❌ 已取消指令：rm -rf /tmp/demo',
    })

    expect(hydrated.content).toBe('')
    expect(hydrated.command).toEqual({
      content: 'rm -rf /tmp/demo',
      status: 'cancelled',
    })
  })

  it('extracts resolved marker and keeps non-marker message content', () => {
    const resolved = extractResolvedCommandMarker(
      '✅ 操作完成：ok\n[RESOLVED_CMD:::confirmed:::rm -rf /tmp/demo:::]'
    )

    expect(resolved).toEqual({
      command: 'rm -rf /tmp/demo',
      status: 'confirmed',
      cleanedContent: '✅ 操作完成：ok',
    })
  })

  it('hydrates resolved marker for confirmed command cards', () => {
    const hydrated = hydrateMessageWithCommand({
      role: 'ai',
      content: '⏳ 背景任務已啟動\n[RESOLVED_CMD:::confirmed:::docker pull ubuntu:::]',
    })

    expect(hydrated.content).toBe('⏳ 背景任務已啟動')
    expect(hydrated.command).toEqual({
      content: 'docker pull ubuntu',
      status: 'confirmed',
    })
  })

  it('hydrates createdAt and resolvedAt timestamps from history payload', () => {
    const hydrated = hydrateMessageWithCommand({
      role: 'ai',
      content: '✅ 操作完成：ok\n[RESOLVED_CMD:::confirmed:::docker pull ubuntu:::]',
      createdAt: '2026-03-10T09:00:00',
      resolvedAt: '2026-03-10T09:00:30',
    })

    expect(hydrated.command).toEqual({
      content: 'docker pull ubuntu',
      status: 'confirmed',
      createdAt: Date.parse('2026-03-10T09:00:00'),
      resolvedAt: Date.parse('2026-03-10T09:00:30'),
    })
  })

  it('hydrates confirmed command text with ASCII colon into resolved payload', () => {
    const hydrated = hydrateMessageWithCommand({
      role: 'ai',
      content: '✅ 已執行指令: ls -la',
    })

    expect(hydrated.content).toBe('')
    expect(hydrated.command).toEqual({
      content: 'ls -la',
      status: 'confirmed',
    })
  })

  it('does not convert unrelated assistant errors into command payloads', () => {
    const hydrated = hydrateMessageWithCommand({
      role: 'ai',
      content: '❌ 伺服器回應異常，請稍後再試。',
    })

    expect(hydrated.content).toBe('❌ 伺服器回應異常，請稍後再試。')
    expect(hydrated.command).toBeUndefined()
  })

  it('ignores resolved command text for non-assistant roles', () => {
    const hydrated = hydrateMessageWithCommand({
      role: 'user',
      content: '❌ 已取消指令：rm -rf /tmp/demo',
    })

    expect(hydrated.content).toBe('❌ 已取消指令：rm -rf /tmp/demo')
    expect(hydrated.command).toBeUndefined()
  })

  it('extractResolvedCommandText returns null for blank commands', () => {
    expect(extractResolvedCommandText('❌ 已取消指令：   ')).toBeNull()
  })

  it('extracts rate-limit marker and keeps non-marker content', () => {
    const marker = extractRateLimitMarker('系統繁忙 [RATE_LIMIT:::60:::]')

    expect(marker).toEqual({
      retryAfterSec: 60,
      remainingKeyCount: null,
      cleanedContent: '系統繁忙 ',
    })
  })

  it('extracts rate-limit marker with remaining key count metadata', () => {
    const marker = extractRateLimitMarker('[RATE_LIMIT:::15:::0:::]')

    expect(marker).toEqual({
      retryAfterSec: 15,
      remainingKeyCount: 0,
      cleanedContent: '',
    })
  })
})
