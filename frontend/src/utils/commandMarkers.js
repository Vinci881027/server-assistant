export const COMMAND_MARKERS = Object.freeze({
  CMD_PREFIX: '[CMD:::',
  CONFIRM_CMD_PREFIX: '[CONFIRM_CMD:::',
  RESOLVED_CMD_PREFIX: '[RESOLVED_CMD:::',
  CMD_SUFFIX: ':::]',
  OFFLOAD_JOB_PREFIX: '[OFFLOAD_JOB:::',
  BG_JOB_PREFIX: '[BG_JOB:::',
  RATE_LIMIT_PREFIX: '[RATE_LIMIT:::',
})

const COMMAND_MARKER_REGEX = /\[(?:CONFIRM_)?CMD:::([\s\S]+?):::\]/
const RESOLVED_COMMAND_MARKER_REGEX = /\n?\[RESOLVED_CMD:::(confirmed|cancelled):::([\s\S]+?):::\]/
const OFFLOAD_JOB_MARKER_REGEX = /\[OFFLOAD_JOB:::(.*?):::\]/
const BG_JOB_MARKER_REGEX = /\[BG_JOB:::(.*?):::\]/
const RATE_LIMIT_MARKER_REGEX = /\[RATE_LIMIT:::(\d+):::(?:(\d+):::)?\]/
const CANCELLED_COMMAND_TEXT_REGEX = /^❌\s*已取消指令[：:]\s*([\s\S]+?)\s*$/
const CONFIRMED_COMMAND_TEXT_REGEX = /^✅\s*已執行指令[：:]\s*([\s\S]+?)\s*$/

function parseTimestampToMs(value) {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed) return null
  const parsed = Date.parse(trimmed)
  return Number.isFinite(parsed) ? parsed : null
}

function extractMarkerBody(content, regex) {
  if (typeof content !== 'string' || !content) return null
  const match = content.match(regex)
  if (!match || !match[1]) return null
  const value = match[1].trim()
  return value || null
}

export function extractCommandMarker(content) {
  if (typeof content !== 'string' || !content) return null
  const match = content.match(COMMAND_MARKER_REGEX)
  if (!match) return null

  return {
    command: (match[1] || '').trim(),
    cleanedContent: content.replace(COMMAND_MARKER_REGEX, ''),
  }
}

export function extractResolvedCommandText(content) {
  if (typeof content !== 'string' || !content) return null

  const cancelledMatch = content.match(CANCELLED_COMMAND_TEXT_REGEX)
  if (cancelledMatch?.[1]) {
    const command = cancelledMatch[1].trim()
    if (!command) return null
    return {
      command,
      status: 'cancelled',
    }
  }

  const confirmedMatch = content.match(CONFIRMED_COMMAND_TEXT_REGEX)
  if (confirmedMatch?.[1]) {
    const command = confirmedMatch[1].trim()
    if (!command) return null
    return {
      command,
      status: 'confirmed',
    }
  }

  return null
}

export function extractResolvedCommandMarker(content) {
  if (typeof content !== 'string' || !content) return null
  const match = content.match(RESOLVED_COMMAND_MARKER_REGEX)
  if (!match || !match[1] || !match[2]) return null

  const status = match[1].trim()
  const command = match[2].trim()
  if (!command) return null

  return {
    command,
    status,
    cleanedContent: content.replace(RESOLVED_COMMAND_MARKER_REGEX, ''),
  }
}

export function hasPendingCommandMarker(content) {
  if (typeof content !== 'string' || !content) return false
  return content.includes(COMMAND_MARKERS.CMD_PREFIX) ||
    content.includes(COMMAND_MARKERS.CONFIRM_CMD_PREFIX)
}

export function extractOffloadJobMarker(content) {
  return extractMarkerBody(content, OFFLOAD_JOB_MARKER_REGEX)
}

export function extractBgJobMarker(content) {
  return extractMarkerBody(content, BG_JOB_MARKER_REGEX)
}

export function extractRateLimitMarker(content) {
  if (typeof content !== 'string' || !content) return null
  const match = content.match(RATE_LIMIT_MARKER_REGEX)
  if (!match?.[1]) return null

  const retryAfterSec = parseInt(match[1], 10)
  if (!Number.isFinite(retryAfterSec) || retryAfterSec <= 0) return null
  const remainingKeyCountRaw = match[2]
  const remainingKeyCount = remainingKeyCountRaw == null
    ? null
    : parseInt(remainingKeyCountRaw, 10)
  if (remainingKeyCountRaw != null && (!Number.isFinite(remainingKeyCount) || remainingKeyCount < 0)) return null

  return {
    retryAfterSec,
    remainingKeyCount,
    cleanedContent: content.replace(RATE_LIMIT_MARKER_REGEX, ''),
  }
}

export function hydrateMessageWithCommand(rawMessage) {
  const base = (rawMessage && typeof rawMessage === 'object')
    ? { ...rawMessage }
    : { role: 'ai', content: '' }
  const createdAtMs = parseTimestampToMs(base.createdAt)
  const resolvedAtMs = parseTimestampToMs(base.resolvedAt)

  const content = typeof base.content === 'string' ? base.content : ''
  if (base.role !== 'ai') {
    return { ...base, content }
  }

  const marker = extractCommandMarker(content)
  if (marker) {
    return {
      ...base,
      content: marker.cleanedContent,
      command: {
        content: marker.command,
        status: 'pending',
        ...(Number.isFinite(createdAtMs) ? { createdAt: createdAtMs } : {}),
      },
    }
  }

  const resolvedMarker = extractResolvedCommandMarker(content)
  if (resolvedMarker) {
    return {
      ...base,
      content: resolvedMarker.cleanedContent,
      command: {
        content: resolvedMarker.command,
        status: resolvedMarker.status,
        ...(Number.isFinite(createdAtMs) ? { createdAt: createdAtMs } : {}),
        ...(Number.isFinite(resolvedAtMs) ? { resolvedAt: resolvedAtMs } : {}),
      },
    }
  }

  const resolvedCommand = extractResolvedCommandText(content)
  if (!resolvedCommand) {
    return { ...base, content }
  }

  return {
    ...base,
    content: '',
    command: {
      content: resolvedCommand.command,
      status: resolvedCommand.status,
      ...(Number.isFinite(createdAtMs) ? { createdAt: createdAtMs } : {}),
      ...(Number.isFinite(resolvedAtMs) ? { resolvedAt: resolvedAtMs } : {}),
    },
  }
}
