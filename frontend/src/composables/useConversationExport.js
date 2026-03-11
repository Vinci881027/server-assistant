import { storeToRefs } from 'pinia'
import { useConversationStore } from '../stores/conversationStore'
import { chatApi } from '../api'
import { hydrateMessageWithCommand } from '../utils/commandMarkers'
import { buildExportTimestamp, downloadTextFile, sanitizeFilenamePart } from '../utils/exportUtils'

const CONVERSATION_EXPORT_PAGE_SIZE = 100
const MAX_CONVERSATION_EXPORT_PAGES = 200

export function useConversationExport() {
  const conversationStore = useConversationStore()
  const { conversations } = storeToRefs(conversationStore)

  function resolveConversationTitle(conversationId) {
    const conversation = conversations.value.find((item) => item.id === conversationId)
    return conversation?.title || '對話紀錄'
  }

  function resolveMessageTimestamp(message) {
    const rawTimestamp = typeof message?.createdAt === 'string'
      ? message.createdAt
      : (typeof message?.timestamp === 'string' ? message.timestamp : '')
    if (!rawTimestamp) return '-'

    const parsedDate = new Date(rawTimestamp)
    return Number.isNaN(parsedDate.getTime()) ? rawTimestamp : parsedDate.toLocaleString()
  }

  function renderConversationMarkdown(conversationId, title, messagesForExport) {
    const lines = [
      `# ${title}`,
      '',
      `- Conversation ID: \`${conversationId}\``,
      `- 匯出時間: ${new Date().toLocaleString()}`,
      `- 訊息數量: ${messagesForExport.length}`,
      '',
      '---',
      '',
    ]

    messagesForExport.forEach((message, index) => {
      const roleLabel = message.role === 'user' ? 'User' : 'Assistant'
      lines.push(`## ${index + 1}. ${roleLabel} (${resolveMessageTimestamp(message)})`)
      lines.push('')

      if (message.command?.content) {
        const commandStatus = message.command.status || 'pending'
        lines.push(`> Command [${commandStatus}]: \`${message.command.content}\``)
        lines.push('')
      }

      const content = typeof message.content === 'string' ? message.content.trim() : ''
      lines.push(content || '_[空內容]_')
      lines.push('')
    })

    return `${lines.join('\n').trimEnd()}\n`
  }

  async function loadConversationHistoryForExport(conversationId) {
    const pages = []
    let nextCursorCreatedAt = null
    let nextCursorId = null
    let reachedPageLimit = true

    for (let pageIndex = 0; pageIndex < MAX_CONVERSATION_EXPORT_PAGES; pageIndex += 1) {
      const options = { limit: CONVERSATION_EXPORT_PAGE_SIZE }
      if (nextCursorCreatedAt && Number.isFinite(nextCursorId)) {
        options.beforeCreatedAt = nextCursorCreatedAt
        options.beforeId = nextCursorId
      }

      const result = await chatApi.getHistory(conversationId, options)
      if (!result?.success) {
        throw new Error(result?.message || '載入對話歷史失敗')
      }

      const payload = result?.data ?? {}
      const pageItems = Array.isArray(payload.messages) ? payload.messages : []
      if (pageItems.length === 0) {
        reachedPageLimit = false
        break
      }

      pages.unshift(pageItems)

      const cursorCreatedAt = typeof payload.nextCursorCreatedAt === 'string'
        ? payload.nextCursorCreatedAt.trim()
        : ''
      const cursorId = Number(payload.nextCursorId)
      if (!cursorCreatedAt || !Number.isFinite(cursorId)) {
        reachedPageLimit = false
        break
      }

      if (cursorCreatedAt === nextCursorCreatedAt && cursorId === nextCursorId) {
        reachedPageLimit = false
        break
      }

      nextCursorCreatedAt = cursorCreatedAt
      nextCursorId = cursorId
    }

    if (reachedPageLimit && pages.length > 0) {
      throw new Error('對話資料過大，請稍後再試')
    }

    return pages.flat().map((message) => hydrateMessageWithCommand(message))
  }

  async function handleExportChat(payload) {
    const chatId = typeof payload?.chatId === 'string' ? payload.chatId : ''
    const exportFormat = payload?.format === 'json' ? 'json' : 'markdown'
    if (!chatId) return

    try {
      const title = resolveConversationTitle(chatId)
      const exportedMessages = await loadConversationHistoryForExport(chatId)
      const filePrefix = `${sanitizeFilenamePart(title, 'conversation')}-${sanitizeFilenamePart(chatId.slice(0, 8), 'chat')}-${buildExportTimestamp()}`

      if (exportFormat === 'json') {
        const jsonText = JSON.stringify({
          version: 1,
          exportedAt: new Date().toISOString(),
          conversationId: chatId,
          title,
          messageCount: exportedMessages.length,
          messages: exportedMessages,
        }, null, 2)
        downloadTextFile(`${filePrefix}.json`, jsonText, 'application/json;charset=utf-8')
        return
      }

      const markdownText = renderConversationMarkdown(chatId, title, exportedMessages)
      downloadTextFile(`${filePrefix}.md`, markdownText, 'text/markdown;charset=utf-8')
    } catch (error) {
      console.error('匯出對話失敗:', error)
      const message = error?.message || '未知錯誤'
      window.alert(`匯出失敗：${message}`)
    }
  }

  return {
    handleExportChat,
  }
}
