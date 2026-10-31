import { isJsonObject } from '@/shared/api/responseValidation'
import {
  clearMatchingJsonItem,
  readValidatedJson,
  writeJson,
} from '@/shared/lib/durableStorage'
import { runWithBrowserLock } from '@/shared/lib/browserLock'
import type { BrowserLockResult } from '@/shared/lib/browserLock'
import { isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'

const STORAGE_KEY_PREFIX = 'baton-pending-access-key-change:v1:'
const ROTATE_OPERATION = 'rotate'
const ROTATION_LOCK_PREFIX = 'baton-access-key-rotation:'

type PendingAccessKeyChange = {
  operation: typeof ROTATE_OPERATION
  idempotencyKey: string
}

function storageKey(teamId: string) {
  return `${STORAGE_KEY_PREFIX}${teamId}`
}

function isPendingAccessKeyChange(value: unknown): value is PendingAccessKeyChange {
  if (!isJsonObject(value)) return false
  const candidate = value as Partial<PendingAccessKeyChange>
  return candidate.operation === ROTATE_OPERATION
    && isValidIdempotencyKey(candidate.idempotencyKey)
}

export function pendingAccessKeyRotation(teamId: string) {
  return readValidatedJson(storageKey(teamId), isPendingAccessKeyChange)?.idempotencyKey ?? null
}

export function idempotencyKeyForAccessKeyRotation(teamId: string): string | null {
  const pendingIdempotencyKey = pendingAccessKeyRotation(teamId)
  if (pendingIdempotencyKey) return pendingIdempotencyKey

  const next: PendingAccessKeyChange = {
    operation: ROTATE_OPERATION,
    idempotencyKey: crypto.randomUUID(),
  }
  return writeJson(storageKey(teamId), next) ? next.idempotencyKey : null
}

export function clearPendingAccessKeyRotation(teamId: string, idempotencyKey: string) {
  return clearMatchingJsonItem(
    storageKey(teamId),
    isPendingAccessKeyChange,
    (pending) => pending.idempotencyKey === idempotencyKey,
  )
}

export function forgetPendingAccessKeyRotation(teamId: string) {
  try {
    window.localStorage.removeItem(storageKey(teamId))
  } catch {
    // 저장소를 쓸 수 없으면 같은 저장소의 접근 키 삭제 실패로 먼저 안내한다.
  }
}

export function clearAllPendingAccessKeyRotations() {
  try {
    const storage = window.localStorage
    Array.from({ length: storage.length }, (_, index) => storage.key(index))
      .filter((key): key is string => key !== null && key.startsWith(STORAGE_KEY_PREFIX))
      .forEach((key) => storage.removeItem(key))
    return true
  } catch {
    return false
  }
}

export async function runWithAccessKeyRotationLock<Value>(
  teamId: string,
  operation: () => Promise<Value>,
): Promise<BrowserLockResult<Value>> {
  return runWithBrowserLock(`${ROTATION_LOCK_PREFIX}${teamId}`, operation)
}
