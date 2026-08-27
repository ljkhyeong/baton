type ValidatedStorageEntry<Value> = {
  storageKey: string
  value: Value
}

type JsonGuard<Value> = (value: unknown) => value is Value
type StoredJsonGuard<Value> = (value: unknown, storageKey: string) => value is Value

export type JsonCleanupResult =
  | 'cleared'
  | 'missing'
  | 'changed'
  | 'storageUnavailable'

export function readValidatedJson<Value>(
  storageKey: string,
  isValid: JsonGuard<Value>,
): Value | null {
  try {
    const storedValue = window.localStorage.getItem(storageKey)
    if (storedValue === null) return null
    const parsed: unknown = JSON.parse(storedValue)
    return isValid(parsed) ? parsed : null
  } catch {
    return null
  }
}

export function scanValidatedJson<Value>(
  storagePrefix: string,
  isValid: StoredJsonGuard<Value>,
): ValidatedStorageEntry<Value>[] | null {
  try {
    const entries: ValidatedStorageEntry<Value>[] = []
    const invalidKeys: string[] = []

    for (let index = 0; index < window.localStorage.length; index += 1) {
      const storageKey = window.localStorage.key(index)
      if (!storageKey?.startsWith(storagePrefix)) continue
      const storedValue = window.localStorage.getItem(storageKey)
      if (storedValue === null) continue

      try {
        const parsed: unknown = JSON.parse(storedValue)
        if (isValid(parsed, storageKey)) {
          entries.push({ storageKey, value: parsed })
        } else {
          invalidKeys.push(storageKey)
        }
      } catch {
        invalidKeys.push(storageKey)
      }
    }

    invalidKeys.forEach((storageKey) => {
      try {
        window.localStorage.removeItem(storageKey)
      } catch {
        // Invalid records remain ignored when browser storage cleanup is unavailable.
      }
    })
    return entries
  } catch {
    return null
  }
}

export function writeJson(storageKey: string, value: unknown) {
  try {
    const serialized = JSON.stringify(value)
    if (serialized === undefined) return false
    window.localStorage.setItem(storageKey, serialized)
    return true
  } catch {
    return false
  }
}

export function removeJsonItem(storageKey: string) {
  try {
    window.localStorage.removeItem(storageKey)
    return true
  } catch {
    return false
  }
}

export function clearMatchingJsonItem<Value>(
  storageKey: string,
  isValid: JsonGuard<Value>,
  matches: (value: Value) => boolean,
): JsonCleanupResult {
  try {
    const storedValue = window.localStorage.getItem(storageKey)
    if (storedValue === null) return 'missing'

    const parsed: unknown = JSON.parse(storedValue)
    if (parsed === null) return 'missing'
    if (!isValid(parsed) || !matches(parsed)) return 'changed'

    return removeJsonItem(storageKey) ? 'cleared' : 'storageUnavailable'
  } catch {
    return 'storageUnavailable'
  }
}

export function isJsonCleanupComplete(result: JsonCleanupResult) {
  return result === 'cleared' || result === 'missing'
}
