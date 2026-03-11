/**
 * Appends a quoted message block to the current draft.
 * Keeps two trailing newlines so the user can continue typing immediately.
 *
 * @param {string} draft
 * @param {string} messageContent
 * @returns {string}
 */
export function appendQuoteToDraft(draft, messageContent) {
  const normalizedDraft = typeof draft === 'string' ? draft.trimEnd() : ''
  const normalizedMessage = typeof messageContent === 'string' ? messageContent.trim() : ''
  if (!normalizedMessage) return normalizedDraft

  const quoteBlock = normalizedMessage
    .split(/\r?\n/)
    .map(line => `> ${line}`)
    .join('\n')

  if (!normalizedDraft) {
    return `${quoteBlock}\n\n`
  }
  return `${normalizedDraft}\n\n${quoteBlock}\n\n`
}
