import { beforeEach, describe, expect, it } from 'vitest'
import { useModelSwitchToast } from '../useModelSwitchToast.js'
import { useToastQueue } from '../useToastQueue.js'

describe('useModelSwitchToast', () => {
  beforeEach(() => {
    useToastQueue().dismissAll()
  })

  it('adds an info toast with default duration when message is valid', () => {
    const queue = useToastQueue()
    const { triggerModelSwitchToast } = useModelSwitchToast()

    triggerModelSwitchToast('  已切換到 qwen-120b  ')

    expect(queue.toasts.value).toHaveLength(1)
    expect(queue.toasts.value[0]).toMatchObject({
      type: 'info',
      message: '已切換到 qwen-120b',
      duration: 2200,
    })
  })

  it('ignores non-string and empty messages', () => {
    const queue = useToastQueue()
    const { triggerModelSwitchToast } = useModelSwitchToast()

    triggerModelSwitchToast(null)
    triggerModelSwitchToast('   ')

    expect(queue.toasts.value).toHaveLength(0)
  })
})
