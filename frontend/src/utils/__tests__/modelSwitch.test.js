import { describe, expect, it } from 'vitest'
import { resolveModelAutoSwitchPlan, resolveModelLabel } from '../modelSwitch.js'

describe('modelSwitch', () => {
  const models = {
    '120b': {
      label: 'GPT OSS 120B',
      available: false,
      suggestAlternative: '70b',
    },
    '70b': {
      label: 'Llama 70B',
      available: true,
    },
    '20b': {
      label: 'GPT OSS 20B',
      available: true,
    },
  }

  it('returns suggested alternative when current model is overloaded', () => {
    const plan = resolveModelAutoSwitchPlan('120b', models)

    expect(plan).toEqual({
      reason: 'suggested',
      currentModelKey: '120b',
      targetModelKey: '70b',
    })
  })

  it('returns first available fallback when suggestion is invalid', () => {
    const overloadedModels = {
      ...models,
      '120b': {
        ...models['120b'],
        suggestAlternative: 'missing-model',
      },
    }

    const plan = resolveModelAutoSwitchPlan('120b', overloadedModels)

    expect(plan).toEqual({
      reason: 'fallback',
      currentModelKey: '120b',
      targetModelKey: '70b',
    })
  })

  it('returns null when no alternative model is available', () => {
    const singleModel = {
      '120b': {
        label: 'GPT OSS 120B',
        available: false,
      },
    }

    expect(resolveModelAutoSwitchPlan('120b', singleModel)).toBeNull()
  })

  it('returns missing plan when selected model no longer exists', () => {
    const plan = resolveModelAutoSwitchPlan('removed-model', models)

    expect(plan).toEqual({
      reason: 'missing',
      currentModelKey: 'removed-model',
      targetModelKey: '70b',
    })
  })

  it('returns null when current model remains available', () => {
    expect(resolveModelAutoSwitchPlan('70b', models)).toBeNull()
  })

  it('resolves model label with fallback to key', () => {
    expect(resolveModelLabel(models, '70b')).toBe('Llama 70B')
    expect(resolveModelLabel(models, 'unknown')).toBe('unknown')
  })
})
