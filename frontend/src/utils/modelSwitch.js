function normalizeModelKey(value) {
  if (typeof value !== 'string') return ''
  return value.trim()
}

function isModelAvailable(config) {
  return Boolean(config) && config.available !== false
}

function findFirstAvailableModelKey(models, excludeKey = '') {
  for (const [key, config] of Object.entries(models || {})) {
    if (key === excludeKey) continue
    if (isModelAvailable(config)) return key
  }
  return ''
}

export function resolveModelLabel(models, modelKey) {
  const normalizedKey = normalizeModelKey(modelKey)
  if (!normalizedKey) return ''

  const config = models?.[normalizedKey]
  if (!config || typeof config !== 'object') return normalizedKey

  const label = typeof config.label === 'string' ? config.label.trim() : ''
  return label || normalizedKey
}

/**
 * Resolve automatic model-switch strategy for unavailable/missing models.
 * Returns null when no auto-switch should happen.
 */
export function resolveModelAutoSwitchPlan(currentModelKey, models) {
  const modelMap = models && typeof models === 'object' ? models : {}
  const modelKeys = Object.keys(modelMap)
  if (modelKeys.length === 0) return null

  const normalizedCurrentKey = normalizeModelKey(currentModelKey)
  const currentConfig = normalizedCurrentKey ? modelMap[normalizedCurrentKey] : null

  if (!currentConfig) {
    const availableCandidate = findFirstAvailableModelKey(modelMap)
    const targetModelKey = availableCandidate || modelKeys[0]
    if (!targetModelKey || targetModelKey === normalizedCurrentKey) return null
    return {
      reason: 'missing',
      currentModelKey: normalizedCurrentKey,
      targetModelKey,
    }
  }

  if (currentConfig.available !== false) {
    return null
  }

  const suggestedKey = normalizeModelKey(currentConfig.suggestAlternative)
  if (suggestedKey && suggestedKey !== normalizedCurrentKey && isModelAvailable(modelMap[suggestedKey])) {
    return {
      reason: 'suggested',
      currentModelKey: normalizedCurrentKey,
      targetModelKey: suggestedKey,
    }
  }

  const fallbackModelKey = findFirstAvailableModelKey(modelMap, normalizedCurrentKey)
  if (fallbackModelKey) {
    return {
      reason: 'fallback',
      currentModelKey: normalizedCurrentKey,
      targetModelKey: fallbackModelKey,
    }
  }

  return null
}
