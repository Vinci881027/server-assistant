import { describe, expect, it } from 'vitest'
import { buildCsvText, sanitizeFilenamePart } from '../exportUtils'

describe('exportUtils', () => {
  it('sanitizes filename part with reserved characters', () => {
    const result = sanitizeFilenamePart(' report:/2026?* ')
    expect(result).toBe('report-2026')
  })

  it('falls back when filename part is empty', () => {
    const result = sanitizeFilenamePart('   ', 'fallback')
    expect(result).toBe('fallback')
  })

  it('escapes CSV fields with quotes and newlines', () => {
    const csv = buildCsvText(
      ['name', 'note'],
      [['alice', 'line1\nline2, "quoted"']]
    )

    expect(csv).toBe('name,note\nalice,"line1\nline2, ""quoted"""')
  })
})
