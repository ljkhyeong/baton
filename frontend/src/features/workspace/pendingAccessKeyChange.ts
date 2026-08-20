import {
  clearMatchingJsonItem,
  readValidatedJson,
  writeJson,
} from '@/shared/lib/durableStorage'
import { runWithBrowserLock } from '@/shared/lib/browserLock'
import type { BrowserLockResult } from '@/shared/lib/browserLock'
import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'

const STORAGE_KEY_PREFIX = 'baton-pending-access-key-change:v1:'
const ROTATE_OPERATION = 'rotate'
const ROTATION_LOCK_PREFIX = 'baton-access-key-rotation:'

type AccessKeyRotationLockResult<Value> = BrowserLockResult<Value>

type PendingAccessKeyChange = {
  operation: typeof ROTATE_OPERATION
  idempotencyKey: string
}

function storageKey(teamId: string) {
  return `${STORAGE_KEY_PREFIX}${teamId}`
}

function lockName(teamId: string) {
  return `${ROTATION_LOCK_PREFIX}${teamId}`
}

function isPendingAccessKeyChange(value: unknown): value is PendingAccessKeyChange {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<PendingAccessKeyChange>
  return candidate.operation === ROTATE_OPERATION
    && isValidIdempotencyKey(candidate.idempotencyKey)
}

function readPendingAccessKeyChange(teamId: string) {
  return readValidatedJson(storageKey(teamId), isPendingAccessKeyChange)
}

function writePendingAccessKeyChange(teamId: string, pending: PendingAccessKeyChange) {
  return writeJson(storageKey(teamId), pending)
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
  return clearMatchingJsonItem(
    storageKey(teamId),
    isPendingAccessKeyChange,
    (pending) => pending.idempotencyKey === idempotencyKey,
  )
}

export async function runWithAccessKeyRotationLock<Value>(
  teamId: string,
  operation: () => Promise<Value>,
): Promise<AccessKeyRotationLockResult<Value>> {
  return runWithBrowserLock(lockName(teamId), operation)
}
