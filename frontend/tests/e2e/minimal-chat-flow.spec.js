import { expect, test } from '@playwright/test'

test('login, send message, and load older history', async ({ page }) => {
  const historyRequests = []
  const streamedMessages = []

  await page.route('**/ping', async (route) => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ ok: true }),
  }))

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname, searchParams } = url

    if (!pathname.startsWith('/api/')) {
      return route.continue()
    }

    const json = (payload) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    })

    if (pathname === '/api/status' && request.method() === 'GET') {
      return json({ success: false })
    }

    if (pathname === '/api/ping' && request.method() === 'GET') {
      return json({ success: true })
    }

    if (pathname === '/api/login' && request.method() === 'POST') {
      return json({
        success: true,
        data: { user: 'tester', isAdmin: false },
        message: '登入成功',
      })
    }

    if (pathname === '/api/ai/models' && request.method() === 'GET') {
      return json({
        success: true,
        data: {
          '20b': {
            label: 'GPT OSS 20B',
            category: 'General',
          },
        },
      })
    }

    if (pathname === '/api/system/info' && request.method() === 'GET') {
      return json({ ip: '127.0.0.1' })
    }

    if (pathname === '/api/ai/conversations' && request.method() === 'GET') {
      return json({
        success: true,
        data: [
          { id: 'conv-1', title: '歷史對話' },
        ],
      })
    }

    if (pathname === '/api/ai/history' && request.method() === 'GET') {
      const offset = searchParams.get('offset')
      const beforeCreatedAt = searchParams.get('beforeCreatedAt')
      const beforeId = searchParams.get('beforeId')
      historyRequests.push({ offset, beforeCreatedAt, beforeId })

      if (!beforeCreatedAt && !beforeId) {
        return json({
          success: true,
          data: {
            messages: [
              { role: 'user', content: '先前使用者訊息' },
              { role: 'ai', content: '先前 AI 回覆' },
            ],
            total: 3,
            nextCursorCreatedAt: '2025-01-01T00:00:00Z',
            nextCursorId: 42,
          },
        })
      }

      if (beforeCreatedAt === '2025-01-01T00:00:00Z' && beforeId === '42') {
        return json({
          success: true,
          data: {
            messages: [
              { role: 'ai', content: '更早的歷史訊息' },
            ],
            total: 3,
            nextCursorCreatedAt: null,
            nextCursorId: null,
          },
        })
      }

      return json({ success: true, data: { messages: [], total: 3 } })
    }

    if (pathname === '/api/ai/stream' && request.method() === 'POST') {
      const payload = request.postDataJSON()
      streamedMessages.push(payload?.message || '')
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: 'data: 這是測試回覆\n\n',
      })
    }

    return route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({ success: false, message: `Unhandled API: ${pathname}` }),
    })
  })

  await page.goto('/')

  const usernameInput = page.locator('input[name="username"]')
  const passwordInput = page.locator('input[name="password"]')
  await expect(usernameInput).toBeVisible()
  await usernameInput.fill('tester')
  await passwordInput.fill('password')
  await page.getByRole('button', { name: '登入' }).click()

  await expect(page.getByText('先前 AI 回覆')).toBeVisible()

  const input = page.getByRole('textbox', { name: '輸入訊息' })
  await input.fill('現在送一則訊息')
  await page.getByRole('button', { name: '發送訊息' }).click()

  await expect(page.getByText('現在送一則訊息')).toBeVisible()
  await expect(page.getByText('這是測試回覆')).toBeVisible()
  await expect.poll(() => streamedMessages.length).toBe(1)

  await page.getByRole('button', { name: '從資料庫載入更多訊息' }).click()
  await expect(page.getByText('更早的歷史訊息')).toBeVisible()
  expect(historyRequests).toEqual([
    { offset: '0', beforeCreatedAt: null, beforeId: null },
    { offset: null, beforeCreatedAt: '2025-01-01T00:00:00Z', beforeId: '42' },
  ])
})
