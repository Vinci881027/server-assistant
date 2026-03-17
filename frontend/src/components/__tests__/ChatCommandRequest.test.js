import { createApp, h, nextTick, reactive } from 'vue';
import ChatCommandRequest from '../ChatCommandRequest.vue';

function mountChatCommandRequest(initialProps = {}, handlerOverrides = {}) {
  const props = reactive({
    command: 'rm -rf /tmp/demo',
    status: 'pending',
    disabled: false,
    ...initialProps,
  });
  const handlers = {
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
    onResendCommand: vi.fn(),
    ...handlerOverrides,
  };

  const container = document.createElement('div');
  document.body.appendChild(container);

  const app = createApp({
    render() {
      return h(ChatCommandRequest, {
        ...props,
        onConfirm: handlers.onConfirm,
        onCancel: handlers.onCancel,
        onResendCommand: handlers.onResendCommand,
      });
    },
  });

  app.mount(container);

  return {
    container,
    props,
    handlers,
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

  it('keeps expired command card visible and offers resend action', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-10T12:00:00Z').getTime();
    vi.setSystemTime(now);
    const onResendCommand = vi.fn();

    const view = mountChatCommandRequest(
      {
        status: 'pending',
        expiresAt: now - 1000,
      },
      { onResendCommand }
    );

    await flushUi();

    const expandedCard = view.container.querySelector('[role="group"]');
    expect(expandedCard).not.toBeNull();
    expect(view.container.textContent).toContain('確認已逾時');

    const resendButton = Array.from(view.container.querySelectorAll('button'))
      .find((button) => button.textContent.includes('重新發送此指令'));
    expect(resendButton).toBeTruthy();
    resendButton.click();

    expect(onResendCommand).toHaveBeenCalledTimes(1);
    expect(onResendCommand).toHaveBeenCalledWith('rm -rf /tmp/demo');

    view.unmount();
  });

  it('returns focus to summary row after collapsing resolved details', async () => {
    vi.useFakeTimers();
    const view = mountChatCommandRequest({ status: 'confirmed' });

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

  it('shows executing state first, then auto-collapses after confirmed state', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-10T12:00:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'pending',
      expiresAt: now + 60_000,
    });

    await flushUi();

    view.props.status = 'executing';
    await flushUi();
    expect(view.container.textContent).toContain('指令執行中');
    expect(view.container.querySelector('.cmd-spinner')).not.toBeNull();

    view.props.status = 'confirmed';
    view.props.resolvedAt = now + 1000;
    await flushUi();
    expect(view.container.querySelector('[role="group"]')).not.toBeNull();

    vi.advanceTimersByTime(1900);
    await flushUi();

    expect(view.container.querySelector('.cmd-summary-row')).not.toBeNull();

    view.unmount();
  });

  it('shows countdown only when less than two minutes remain', async () => {
    vi.useFakeTimers();
    const now = new Date('2026-03-11T12:00:00Z').getTime();
    vi.setSystemTime(now);

    const view = mountChatCommandRequest({
      status: 'pending',
      expiresAt: now + 6 * 60 * 1000,
    });

    await flushUi();
    expect(view.container.querySelector('.countdown-pill')).toBeNull();
    expect(view.container.textContent).not.toContain('剩餘時間：');

    vi.setSystemTime(now + 3 * 60 * 1000);
    vi.advanceTimersByTime(1000);
    await flushUi();
    expect(view.container.querySelector('.countdown-pill')).toBeNull();
    expect(view.container.textContent).not.toContain('剩餘時間：');

    vi.setSystemTime(now + (4 * 60 + 1) * 1000);
    vi.advanceTimersByTime(1000);
    await flushUi();
    expect(view.container.querySelector('.countdown-danger')).not.toBeNull();
    expect(view.container.textContent).toContain('剩餘時間：');

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

    expect(view.container.querySelector('[role="group"]')).not.toBeNull();
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

    const summaryRow = view.container.querySelector('.cmd-summary-row');
    expect(summaryRow).not.toBeNull();
    summaryRow.click();
    await flushUi();

    expect(view.container.textContent).toContain('高風險說明：永久刪除檔案，無法復原');
    expect(view.container.textContent).toContain('建立時間：');

    view.unmount();
  });
});
