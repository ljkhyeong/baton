import {
  clearMatchingVerifiedJsonItem,
  readValidatedJson,
  writeVerifiedJson,
} from '@/shared/lib/durableStorage'
import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'

const STORAGE_KEY_PREFIX = 'baton-pending-access-key-change:v1:'
const ROTATE_OPERATION = 'rotate'
const ROTATION_LOCK_PREFIX = 'baton-access-key-rotation:'

export type AccessKeyRotationLockResult<Value> =
  | { status: 'completed'; value: Value }
  | { status: 'busy' }
  | { status: 'unsupported' }
  | { status: 'failed'; error: unknown }

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

function browserLockManager():
  | { status: 'ready'; lockManager: LockManager }
  | { status: 'unsupported' }
  | { status: 'failed'; error: unknown } {
  try {
    const lockManager = navigator.locks as LockManager | undefined
    return lockManager
      ? { status: 'ready', lockManager }
      : { status: 'unsupported' }
  } catch (error) {
    return { status: 'failed', error }
  }
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
  return writeVerifiedJson(storageKey(teamId), pending)
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
  return clearMatchingVerifiedJsonItem(
    storageKey(teamId),
    isPendingAccessKeyChange,
    (pending) => pending.idempotencyKey === idempotencyKey,
  )
}

export async function runWithAccessKeyRotationLock<Value>(
  teamId: string,
  operation: () => Promise<Value>,
): Promise<AccessKeyRotationLockResult<Value>> {
  const lockManagerResult = browserLockManager()
  if (lockManagerResult.status !== 'ready') return lockManagerResult

  try {
    return await lockManagerResult.lockManager.request(
      lockName(teamId),
      { ifAvailable: true },
      async (lock): Promise<AccessKeyRotationLockResult<Value>> => {
        if (!lock) return { status: 'busy' }
        try {
          return { status: 'completed', value: await operation() }
        } catch (error) {
          return { status: 'failed', error }
        }
      },
    )
  } catch (error) {
    return { status: 'failed', error }
  }
}
