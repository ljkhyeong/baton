import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'

const STORAGE_KEY_PREFIX = 'baton-pending-access-key-change:v1:'
const ROTATE_OPERATION = 'rotate'

type PendingAccessKeyChange = {
  operation: typeof ROTATE_OPERATION
  idempotencyKey: string
}

function storageKey(teamId: string) {
  return `${STORAGE_KEY_PREFIX}${teamId}`
}

function isPendingAccessKeyChange(value: unknown): value is PendingAccessKeyChange {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<PendingAccessKeyChange>
  return candidate.operation === ROTATE_OPERATION
    && isValidIdempotencyKey(candidate.idempotencyKey)
}

function readPendingAccessKeyChange(teamId: string) {
  try {
    const storedValue = window.localStorage.getItem(storageKey(teamId))
    if (storedValue === null) return null
    try {
      const parsed: unknown = JSON.parse(storedValue)
      if (isPendingAccessKeyChange(parsed)) return parsed
    } catch {
      // A malformed value is replaced only after a verified durable write.
    }
    return null
  } catch {
    return null
  }
}

function writePendingAccessKeyChange(teamId: string, pending: PendingAccessKeyChange) {
  const serialized = JSON.stringify(pending)
  try {
    window.localStorage.setItem(storageKey(teamId), serialized)
    return window.localStorage.getItem(storageKey(teamId)) === serialized
  } catch {
    return false
  }
}

export function pendingAccessKeyRotation(teamId: string) {
  return readPendingAccessKeyChange(teamId)?.idempotencyKey ?? null
}

export function idempotencyKeyForAccessKeyRotation(teamId: string): string | null {
  const pendingIdempotencyKey = pendingAccessKeyRotation(teamId)
  if (pendingIdempotencyKey) return pendingIdempotencyKey

  const next: PendingAccessKeyChange = {
    operation: ROTATE_OPERATION,
    idempotencyKey: generateIdempotencyKey(),
  }
  return writePendingAccessKeyChange(teamId, next) ? next.idempotencyKey : null
}

export function clearPendingAccessKeyRotation(teamId: string, idempotencyKey: string) {
  const pending = readPendingAccessKeyChange(teamId)
  if (pending?.idempotencyKey !== idempotencyKey) return

  try {
    window.localStorage.removeItem(storageKey(teamId))
  } catch {
    // A stale pending value is safer than losing a recoverable replay key.
  }
}
