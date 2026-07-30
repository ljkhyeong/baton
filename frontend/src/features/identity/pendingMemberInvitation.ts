import {
  clearMatchingVerifiedJsonItem,
  readValidatedJson,
  writeVerifiedJson,
} from '@/shared/lib/durableStorage'
import { generateCanonicalUuid } from '@/shared/lib/idempotencyKey'

const STORAGE_KEY_PREFIX = 'baton-pending-member-invitation:v1:'
const LOCK_PREFIX = 'baton-member-invitation:'
const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

export type PendingMemberInvitation = {
  teamId: string
  memberId: string
  idempotencyKey: string
}

export type MemberInvitationLockResult<Value> =
  | { status: 'completed'; value: Value }
  | { status: 'busy' }
  | { status: 'unsupported' }
  | { status: 'failed'; error: unknown }

export type PreparePendingMemberInvitationResult =
  | { status: 'ready'; record: PendingMemberInvitation; recovered: boolean }
  | { status: 'different-member'; record: PendingMemberInvitation }
  | { status: 'storage-unavailable' }

function storageKey(teamId: string) {
  return `${STORAGE_KEY_PREFIX}${teamId}`
}

function isPendingMemberInvitation(value: unknown): value is PendingMemberInvitation {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<PendingMemberInvitation>
  return typeof candidate.teamId === 'string'
    && candidate.teamId.length > 0
    && typeof candidate.memberId === 'string'
    && candidate.memberId.length > 0
    && typeof candidate.idempotencyKey === 'string'
    && CANONICAL_UUID_PATTERN.test(candidate.idempotencyKey)
}

export function pendingMemberInvitation(teamId: string) {
  const record = readValidatedJson(storageKey(teamId), isPendingMemberInvitation)
  return record?.teamId === teamId ? record : null
}

export function preparePendingMemberInvitation(
  teamId: string,
  memberId: string,
): PreparePendingMemberInvitationResult {
  const existing = pendingMemberInvitation(teamId)
  if (existing) {
    return existing.memberId === memberId
      ? { status: 'ready', record: existing, recovered: true }
      : { status: 'different-member', record: existing }
  }

  const record: PendingMemberInvitation = {
    teamId,
    memberId,
    idempotencyKey: generateCanonicalUuid(),
  }
  return writeVerifiedJson(storageKey(teamId), record)
    ? { status: 'ready', record, recovered: false }
    : { status: 'storage-unavailable' }
}

export function clearPendingMemberInvitation(record: PendingMemberInvitation) {
  return clearMatchingVerifiedJsonItem(
    storageKey(record.teamId),
    isPendingMemberInvitation,
    (candidate) =>
      candidate.teamId === record.teamId
      && candidate.memberId === record.memberId
      && candidate.idempotencyKey === record.idempotencyKey,
  )
}

export async function runWithMemberInvitationLock<Value>(
  teamId: string,
  operation: () => Promise<Value>,
): Promise<MemberInvitationLockResult<Value>> {
  let lockManager: LockManager | undefined
  try {
    lockManager = navigator.locks as LockManager | undefined
  } catch (error) {
    return { status: 'failed', error }
  }
  if (!lockManager) return { status: 'unsupported' }

  try {
    return await lockManager.request(
      `${LOCK_PREFIX}${teamId}`,
      { ifAvailable: true },
      async (lock): Promise<MemberInvitationLockResult<Value>> => {
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
