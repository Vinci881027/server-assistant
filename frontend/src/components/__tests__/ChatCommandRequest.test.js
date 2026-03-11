import { createApp, h, nextTick, reactive } from 'vue';
import ChatCommandRequest from '../ChatCommandRequest.vue';

function mountChatCommandRequest(initialProps = {}) {
  const props = reactive({
    command: 'rm -rf /tmp/demo',
    status: 'pending',
    disabled: false,
    ...initialProps,
  });

  const container = document.createElement('div');
  document.body.appendChild(container);

  const app = createApp({
    render() {
      return h(ChatCommandRequest, props);
    },
  });

  app.mount(container);

  return {
    container,
    props,
    unmount() {
      app.unmount();
      container.remove();
    },
  };
}

async function flushUi() {
  await nextTick();
  await nextTick();
}

describe('ChatCommandRequest', () => {
  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = '';
  });

  it('auto-collapses timed-out pending command and shows timeout summary', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-10T12:00:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'pending',
      expiresAt: now - 1000,
    });

    await flushUi();

    const summaryRow = view.container.querySelector('.cmd-summary-row');
    expect(summaryRow).not.toBeNull();
    expect(view.container.textContent).toContain('已逾時');
    expect(view.container.querySelector('[role="group"]')).toBeNull();

    summaryRow.click();
    await flushUi();

    const expandedCard = view.container.querySelector('[role="group"]');
    expect(expandedCard).not.toBeNull();
    const collapseHeader = view.container.querySelector('.cmd-card-header-collapsible');
    expect(collapseHeader).not.toBeNull();

    collapseHeader.click();
    await flushUi();
    expect(view.container.querySelector('.cmd-summary-row')).not.toBeNull();

    view.unmount();
  });

  it('returns focus to summary row after collapsing resolved details', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-10T12:00:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'pending',
      expiresAt: now - 1000,
    });

    await flushUi();

    const summaryRow = view.container.querySelector('.cmd-summary-row');
    expect(summaryRow).not.toBeNull();

    summaryRow.click();
    await flushUi();

    const collapseHeader = view.container.querySelector('.cmd-card-header-collapsible');
    expect(collapseHeader).not.toBeNull();

    collapseHeader.click();
    await flushUi();

    const nextSummaryRow = view.container.querySelector('.cmd-summary-row');
    expect(nextSummaryRow).not.toBeNull();
    expect(document.activeElement).toBe(nextSummaryRow);

    view.unmount();
  });

  it('collapses automatically when countdown reaches zero', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-10T12:00:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'pending',
      expiresAt: now + 1500,
    });

    await flushUi();
    expect(view.container.querySelector('[role="group"]')).not.toBeNull();

    vi.advanceTimersByTime(2000);
    await flushUi();
    vi.runOnlyPendingTimers();
    await flushUi();

    expect(view.container.querySelector('.cmd-summary-row')).not.toBeNull();
    expect(view.container.textContent).toContain('已逾時');

    view.unmount();
  });

  it('uses deadline timestamp for initially expired history commands', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-11T12:00:00Z').getTime();
    const expiresAt = new Date('2026-03-10T09:30:15Z').getTime();
    const createdAt = new Date('2026-03-10T09:00:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'pending',
      expiresAt,
      createdAt,
    });

    await flushUi();

    const expectedExpiresAt = new Intl.DateTimeFormat('zh-TW', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(new Date(expiresAt));
    const expectedCreatedAt = new Intl.DateTimeFormat('zh-TW', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(new Date(createdAt));

    expect(view.container.querySelector('.cmd-summary-time')).toBeNull();

    const summaryRow = view.container.querySelector('.cmd-summary-row');
    summaryRow.click();
    await flushUi();
    expect(view.container.textContent).toContain(`逾時時間：${expectedExpiresAt}`);
    expect(view.container.textContent).toContain(`建立時間：${expectedCreatedAt}`);

    view.unmount();
  });

  it('keeps risk description visible in expanded resolved card', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-11T12:00:00Z').getTime();
    const createdAt = new Date('2026-03-10T09:00:00Z').getTime();
    const resolvedAt = new Date('2026-03-10T09:01:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'confirmed',
      createdAt,
      resolvedAt,
      command: 'rm -rf /tmp/demo',
    });

    await flushUi();
    expect(view.container.querySelector('.cmd-summary-time')).toBeNull();

    const summaryRow = view.container.querySelector('.cmd-summary-row');
    expect(summaryRow).not.toBeNull();

    summaryRow.click();
    await flushUi();

    expect(view.container.textContent).toContain('高風險說明：永久刪除檔案，無法復原');
    expect(view.container.textContent).toContain('建立時間：');

    view.unmount();
  });
});
