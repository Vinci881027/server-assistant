import { expect, test } from '@playwright/test'

function jsonBody(payload) {
  return JSON.stringify(payload)
}

function historyPayload({
  messages = [],
  total = messages.length,
  nextCursorCreatedAt = null,
  nextCursorId = null,
} = {}) {
  return {
    success: true,
    data: {
      messages,
      total,
      nextCursorCreatedAt,
      nextCursorId,
    },
  }
}

function asPagedItems(items) {
  return {
    success: true,
    data: {
      items,
      page: 0,
      size: 100,
      totalElements: items.length,
      totalPages: items.length > 0 ? 1 : 0,
      hasNext: false,
    },
  }
}

async function setupMockApi(page, options = {}) {
  const historyRequests = []
  const streamMessages = []
  const confirmRequests = []
  const cancelRequests = []
  const adminRequests = {
    audit: [],
    history: [],
  }

  const loginUser = options.loginUser ?? 'tester'
  const loginIsAdmin = options.loginIsAdmin ?? false
  const models = options.models ?? {
    '20b': { label: 'GPT OSS 20B', category: 'General' },
  }
  const conversations = options.conversations ?? [{ id: 'conv-1', title: '歷史對話' }]
  const streamQueue = [...(options.streamQueue ?? [
    { sse: 'data: 這是測試回覆\n\n' },
  ])]
  const historyQueue = [...(options.historyQueue ?? [
    historyPayload({
      messages: [
        { role: 'user', content: '先前使用者訊息' },
        { role: 'ai', content: '先前 AI 回覆' },
      ],
      total: 2,
    }),
  ])]
  const adminUsers = options.adminUsers ?? ['tester']
  const adminAuditByUser = options.adminAuditByUser ?? {}
  const adminHistoryByUser = options.adminHistoryByUser ?? {}

  await page.route('**/ping', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: jsonBody({ ok: true }),
    })
  })

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname, searchParams } = url

    if (!pathname.startsWith('/api/')) {
      await route.continue()
      return
    }

    const respondJson = async (payload, status = 200) => {
      await route.fulfill({
        status,
        contentType: 'application/json',
        body: jsonBody(payload),
      })
    }

    const dequeue = (queue, fallback) => {
      if (queue.length === 0) return fallback
      return queue.shift()
    }

    if (pathname === '/api/status' && request.method() === 'GET') {
      await respondJson({ success: false })
      return
    }

    if (pathname === '/api/ping' && request.method() === 'GET') {
      await respondJson({ success: true })
      return
    }

    if (pathname === '/api/login' && request.method() === 'POST') {
      await respondJson({
        success: true,
        data: { user: loginUser, isAdmin: loginIsAdmin },
        message: '登入成功',
      })
      return
    }

    if (pathname === '/api/logout' && request.method() === 'POST') {
      await respondJson({ success: true, message: '登出成功' })
      return
    }

    if (pathname === '/api/ai/models' && request.method() === 'GET') {
      await respondJson({ success: true, data: models })
      return
    }

    if (pathname === '/api/system/info' && request.method() === 'GET') {
      await respondJson({ ip: '127.0.0.1' })
      return
    }

    if (pathname === '/api/ai/conversations' && request.method() === 'GET') {
      await respondJson({ success: true, data: conversations })
      return
    }

    if (pathname === '/api/ai/conversations/new' && request.method() === 'POST') {
      await respondJson({ success: true, data: 'conv-new' })
      return
    }

    if (pathname === '/api/ai/history' && request.method() === 'GET') {
      historyRequests.push({
        offset: searchParams.get('offset'),
        beforeCreatedAt: searchParams.get('beforeCreatedAt'),
        beforeId: searchParams.get('beforeId'),
      })

      const responseDef = dequeue(historyQueue, historyPayload())
      if (responseDef && typeof responseDef === 'object' && 'status' in responseDef) {
        await route.fulfill({
          status: responseDef.status,
          contentType: responseDef.contentType ?? 'application/json',
          body: typeof responseDef.body === 'string' ? responseDef.body : jsonBody(responseDef.body ?? {}),
        })
        return
      }

      await respondJson(responseDef)
      return
    }

    if (pathname === '/api/ai/stream' && request.method() === 'POST') {
      try {
        const payload = request.postDataJSON()
        streamMessages.push(payload?.message || '')
      } catch {
        streamMessages.push('')
      }

      const responseDef = dequeue(streamQueue, { sse: 'data: 這是測試回覆\n\n' })
      if (responseDef && typeof responseDef === 'object' && 'sse' in responseDef) {
        await route.fulfill({
          status: responseDef.status ?? 200,
          contentType: responseDef.contentType ?? 'text/event-stream',
          body: responseDef.sse,
        })
        return
      }

      await route.fulfill({
        status: responseDef?.status ?? 500,
        contentType: responseDef?.contentType ?? 'application/json',
        body: typeof responseDef?.body === 'string'
          ? responseDef.body
          : jsonBody(responseDef?.body ?? { success: false, message: 'stream error' }),
      })
      return
    }

    if (pathname === '/api/ai/confirm-command' && request.method() === 'POST') {
      confirmRequests.push(request.postDataJSON())
      await respondJson({ success: true, data: '✅ 指令已執行完成。' })
      return
    }

    if (pathname === '/api/ai/cancel-command' && request.method() === 'POST') {
      cancelRequests.push(request.postDataJSON())
      await respondJson({ success: true, data: '已取消' })
      return
    }

    if (pathname === '/api/admin/users' && request.method() === 'GET') {
      await respondJson({ success: true, data: adminUsers })
      return
    }

    if (pathname.startsWith('/api/admin/audit/') && request.method() === 'GET') {
      const username = decodeURIComponent(pathname.replace('/api/admin/audit/', ''))
      adminRequests.audit.push(username)
      await respondJson(asPagedItems(adminAuditByUser[username] ?? []))
      return
    }

    if (pathname.startsWith('/api/admin/history/') && request.method() === 'GET') {
      const username = decodeURIComponent(pathname.replace('/api/admin/history/', ''))
      adminRequests.history.push(username)
      await respondJson(asPagedItems(adminHistoryByUser[username] ?? []))
      return
    }

    if (pathname === '/api/admin/models' && request.method() === 'GET') {
      await respondJson([])
      return
    }

    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: jsonBody({ success: false, message: `Unhandled API: ${pathname}` }),
    })
  })

  return {
    historyRequests,
    streamMessages,
    confirmRequests,
    cancelRequests,
    adminRequests,
  }
}

async function loginAs(page, { username = 'tester', password = 'password' } = {}) {
  const usernameInput = page.locator('input[name="username"]')
  const passwordInput = page.locator('input[name="password"]')

  await expect(usernameInput).toBeVisible()
  await usernameInput.fill(username)
  await passwordInput.fill(password)
  await page.getByRole('button', { name: '登入' }).click()
  await expect(page.getByRole('textbox', { name: '輸入訊息' })).toBeVisible()
}

test('login, send message, and load older history', async ({ page }) => {
  const mock = await setupMockApi(page, {
    historyQueue: [
      historyPayload({
        messages: [
          { role: 'user', content: '先前使用者訊息' },
          { role: 'ai', content: '先前 AI 回覆' },
        ],
        total: 3,
        nextCursorCreatedAt: '2025-01-01T00:00:00Z',
        nextCursorId: 42,
      }),
      historyPayload({
        messages: [
          { role: 'ai', content: '更早的歷史訊息' },
        ],
        total: 3,
      }),
    ],
  })

  await page.goto('/')
  await loginAs(page)

  await expect(page.getByText('先前 AI 回覆')).toBeVisible()

  const input = page.getByRole('textbox', { name: '輸入訊息' })
  await input.fill('現在送一則訊息')
  await page.getByRole('button', { name: '發送訊息' }).click()

  await expect(page.getByText('現在送一則訊息')).toBeVisible()
  await expect(page.getByText('這是測試回覆')).toBeVisible()
  await expect.poll(() => mock.streamMessages.length).toBe(1)

  await page.getByRole('button', { name: '從資料庫載入更多訊息' }).click()
  await expect(page.getByText('更早的歷史訊息')).toBeVisible()
  expect(mock.historyRequests).toEqual([
    { offset: '0', beforeCreatedAt: null, beforeId: null },
    { offset: null, beforeCreatedAt: '2025-01-01T00:00:00Z', beforeId: '42' },
  ])
})

test('admin can open dashboard and inspect user audit/history', async ({ page }) => {
  const mock = await setupMockApi(page, {
    loginUser: 'admin',
    loginIsAdmin: true,
    adminUsers: ['admin', 'tester'],
    adminAuditByUser: {
      tester: [
        {
          id: 10,
          executionTime: '2025-02-02T10:00:00Z',
          success: true,
          commandType: 'READ',
          command: 'ls -la',
          output: 'ok',
        },
      ],
    },
    adminHistoryByUser: {
      tester: [
        {
          timestamp: '2025-02-02T10:01:00Z',
          role: 'user',
          content: '查詢磁碟使用率',
        },
      ],
    },
  })

  await page.goto('/')
  await loginAs(page, { username: 'admin' })

  await page.getByRole('button', { name: '開啟管理面板' }).click()
  await expect(page.getByRole('heading', { name: 'Admin Dashboard' })).toBeVisible()

  await page.getByRole('button', { name: 'tester' }).click()
  await expect(page.getByText('ls -la')).toBeVisible()

  await page.getByRole('button', { name: '對話紀錄' }).click()
  await expect(page.getByText('查詢磁碟使用率')).toBeVisible()

  await page.getByRole('button', { name: '關閉管理面板' }).click()
  await expect(page.getByRole('textbox', { name: '輸入訊息' })).toBeVisible()

  expect(mock.adminRequests.audit).toContain('tester')
  expect(mock.adminRequests.history).toContain('tester')
})

test('supports command confirm and cancel flows', async ({ page }) => {
  const mock = await setupMockApi(page, {
    streamQueue: [
      { sse: 'data: [CMD:::sudo systemctl restart nginx:::]\n\n' },
      { sse: 'data: [CMD:::rm -rf /tmp/cache:::]\n\n' },
    ],
  })

  await page.goto('/')
  await loginAs(page)

  const input = page.getByRole('textbox', { name: '輸入訊息' })

  await input.fill('請執行第一個高風險指令')
  await page.getByRole('button', { name: '發送訊息' }).click()

  await expect(page.getByText('sudo systemctl restart nginx')).toBeVisible()
  await page.getByRole('button', { name: '確認執行' }).first().click()

  await expect(page.getByText('指令已確認執行')).toBeVisible()
  await expect(page.getByText('✅ 指令已執行完成。')).toBeVisible()

  await input.fill('請執行第二個高風險指令')
  await page.getByRole('button', { name: '發送訊息' }).click()

  await expect(page.getByText('rm -rf /tmp/cache')).toBeVisible()
  await page.getByRole('button', { name: '取消' }).last().click()
  await expect(page.getByText('操作已取消')).toBeVisible()

  expect(mock.confirmRequests).toHaveLength(1)
  expect(mock.confirmRequests[0]).toMatchObject({
    command: 'sudo systemctl restart nginx',
    conversationId: 'conv-1',
  })

  expect(mock.cancelRequests).toHaveLength(1)
  expect(mock.cancelRequests[0]).toMatchObject({
    command: 'rm -rf /tmp/cache',
    conversationId: 'conv-1',
  })
})

test('shows offline banner and allows immediate retry on transient stream errors', async ({ page }) => {
  const mock = await setupMockApi(page, {
    streamQueue: [
      {
        status: 503,
        body: { success: false, message: 'Service unavailable' },
      },
      { sse: 'data: 重試後成功\n\n' },
    ],
  })

  await page.goto('/')
  await loginAs(page)

  await page.evaluate(() => {
    window.dispatchEvent(new Event('offline'))
  })
  await expect(page.getByText('網路連線中斷，恢復後會自動重新載入目前對話歷史。')).toBeVisible()

  await page.evaluate(() => {
    window.dispatchEvent(new Event('online'))
  })
  await expect(page.getByText('網路連線中斷，恢復後會自動重新載入目前對話歷史。')).toBeHidden()

  const input = page.getByRole('textbox', { name: '輸入訊息' })
  await input.fill('測試暫時性失敗重試')
  await page.getByRole('button', { name: '發送訊息' }).click()

  await expect(page.getByText(/重試中 \(2\/3\)/)).toBeVisible()
  await page.getByRole('button', { name: '立即重試' }).click()

  await expect(page.getByText('重試後成功')).toBeVisible()
  await expect.poll(() => mock.streamMessages.length).toBe(2)
})

test('renders history error state and can retry loading history', async ({ page }) => {
  const mock = await setupMockApi(page, {
    historyQueue: [
      {
        status: 500,
        body: { success: false, message: 'history temporarily unavailable' },
      },
      historyPayload({
        messages: [{ role: 'ai', content: '歷史重載成功' }],
        total: 1,
      }),
    ],
  })

  await page.goto('/')
  await loginAs(page)

  await expect(page.getByText('載入對話失敗')).toBeVisible()
  await page.getByRole('button', { name: '重試' }).click()

  await expect(page.getByText('歷史重載成功')).toBeVisible()
  expect(mock.historyRequests).toHaveLength(2)
})
