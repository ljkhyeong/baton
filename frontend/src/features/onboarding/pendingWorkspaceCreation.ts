import type { WorkspaceCreationIntent } from '@/features/workspace/types'
import {
  clearMatchingVerifiedJsonItem,
  removeVerifiedJsonItem,
  scanValidatedJson,
  writeVerifiedJson,
} from '@/shared/lib/durableStorage'
import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'
import {
  MAX_INITIAL_MEMBER_COUNT,
  MAX_MEMBER_NAME_LENGTH,
  MAX_WORKSPACE_NAME_LENGTH,
} from './workspaceCreationConstraints'

const PENDING_CREATION_STORAGE_PREFIX = 'baton-pending-workspace-creation:v3:'
const LEGACY_SINGLE_STORAGE_KEY = 'baton-pending-workspace-creation:v1'
const LEGACY_COLLECTION_STORAGE_KEY = 'baton-pending-workspace-creations:v2'
const LEGACY_COLLECTION_VERSION = 2
const MAX_PENDING_CREATIONS = 5
const LOCAL_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const WORKSPACE_CREATION_LOCK_NAME = 'baton-workspace-creation'

type PendingWorkspaceCreation = {
  normalizedPayload: string
  idempotencyKey: string
  createdAt: number
}

type LocatedPendingCreation = {
  storageKey: string
  pending: PendingWorkspaceCreation
}

export type PendingWorkspaceCreationItem = Readonly<{
  request: WorkspaceCreationIntent
  idempotencyKey: string
  createdAt: number
}>

export type PendingWorkspaceCreationListResult =
  | { status: 'ready'; items: readonly PendingWorkspaceCreationItem[] }
  | { status: 'unavailable' }

export type WorkspaceCreationPreparation =
  | { status: 'ready'; idempotencyKey: string }
  | { status: 'blocked'; reason: 'storageUnavailable' | 'pendingLimitReached' }

export type PendingWorkspaceRecoveryPreparation =
  | { status: 'ready'; idempotencyKey: string }
  | { status: 'blocked'; reason: 'missing' | 'changed' | 'storageUnavailable' }

export type PendingWorkspaceDiscardResult =
  | 'discarded'
  | 'missing'
  | 'changed'
  | 'busy'
  | 'unsupported'
  | 'storageUnavailable'

export type WorkspaceCreationLockResult<Value> =
  | { status: 'completed'; value: Value }
  | { status: 'busy' }
  | { status: 'unsupported' }

function browserLockManager() {
  try {
    return navigator.locks as LockManager | undefined
  } catch {
    return undefined
  }
}

function normalizePayload(request: WorkspaceCreationIntent) {
  const workspace = {
    teamName: request.teamName.trim(),
    seasonName: request.seasonName.trim(),
    startDate: request.startDate.trim(),
    endDate: request.endDate.trim(),
    memberNames: request.memberNames.map((name) => name.trim()).sort(),
  }
  if (request.mode === 'legacy') return JSON.stringify(workspace)
  return JSON.stringify({
    ...workspace,
    mode: request.mode,
    expectedAccountId: request.expectedAccountId,
    ownerMemberName: request.ownerMemberName.trim(),
  })
}

function isValidLocalDate(value: string) {
  const match = LOCAL_DATE_PATTERN.exec(value)
  if (!match) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0)
  const daysInMonth = [
    31,
    leapYear ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ]
  return month >= 1 && month <= 12 && day >= 1 && day <= daysInMonth[month - 1]!
}

function isNormalizedPayload(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const parsed = JSON.parse(value) as Partial<WorkspaceCreationIntent> | null
    if (!parsed || typeof parsed !== 'object') return false
    if (typeof parsed.teamName !== 'string'
      || !parsed.teamName
      || parsed.teamName.length > MAX_WORKSPACE_NAME_LENGTH
      || typeof parsed.seasonName !== 'string'
      || !parsed.seasonName
      || parsed.seasonName.length > MAX_WORKSPACE_NAME_LENGTH
      || typeof parsed.startDate !== 'string'
      || typeof parsed.endDate !== 'string'
      || !isValidLocalDate(parsed.startDate)
      || !isValidLocalDate(parsed.endDate)
      || parsed.startDate > parsed.endDate
      || !Array.isArray(parsed.memberNames)
      || !parsed.memberNames.length
      || parsed.memberNames.length > MAX_INITIAL_MEMBER_COUNT
      || !parsed.memberNames.every((name) => (
        typeof name === 'string'
        && Boolean(name)
        && name.length <= MAX_MEMBER_NAME_LENGTH
        && name === name.trim()
      ))
      || new Set(parsed.memberNames).size !== parsed.memberNames.length) {
      return false
    }
    if (parsed.mode === 'session') {
      if (typeof parsed.expectedAccountId !== 'string'
        || !UUID_PATTERN.test(parsed.expectedAccountId)
        || typeof parsed.ownerMemberName !== 'string'
        || !parsed.ownerMemberName
        || parsed.ownerMemberName !== parsed.ownerMemberName.trim()
        || parsed.ownerMemberName.length > MAX_MEMBER_NAME_LENGTH
        || !parsed.memberNames.includes(parsed.ownerMemberName)) {
        return false
      }
      return normalizePayload(parsed as WorkspaceCreationIntent) === value
    }
    if (parsed.mode !== undefined
      || 'expectedAccountId' in parsed
      || 'ownerMemberName' in parsed) {
      return false
    }
    return normalizePayload({
      ...(parsed as Omit<WorkspaceCreationIntent, 'mode'>),
      mode: 'legacy',
    } as WorkspaceCreationIntent) === value
  } catch {
    return false
  }
}

function parseNormalizedPayload(value: string): WorkspaceCreationIntent {
  const parsed = JSON.parse(value) as WorkspaceCreationIntent
  if (parsed.mode === 'session') return parsed
  return { ...parsed, mode: 'legacy' }
}

function isLegacyPendingCreation(value: unknown): value is Omit<PendingWorkspaceCreation, 'createdAt'> {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<PendingWorkspaceCreation>
  return isNormalizedPayload(candidate.normalizedPayload)
    && isValidIdempotencyKey(candidate.idempotencyKey)
}

function isPendingWorkspaceCreation(value: unknown): value is PendingWorkspaceCreation {
  if (!isLegacyPendingCreation(value)) return false
  const candidate = value as Partial<PendingWorkspaceCreation>
  return typeof candidate.createdAt === 'number'
    && Number.isFinite(candidate.createdAt)
    && candidate.createdAt >= 0
}

function storageKey(idempotencyKey: string) {
  return `${PENDING_CREATION_STORAGE_PREFIX}${idempotencyKey}`
}

function readPendingCreations(): LocatedPendingCreation[] | null {
  const entries = scanValidatedJson(
    PENDING_CREATION_STORAGE_PREFIX,
    (value, key): value is PendingWorkspaceCreation =>
      isPendingWorkspaceCreation(value) && storageKey(value.idempotencyKey) === key,
  )
  return entries?.map(({ storageKey, value: pending }) => ({ storageKey, pending })) ?? null
}

function writePendingCreation(pending: PendingWorkspaceCreation) {
  return writeVerifiedJson(storageKey(pending.idempotencyKey), pending)
}

function removePendingCreation(key: string) {
  return removeVerifiedJsonItem(key)
}

function pendingMatchesItem(
  pending: PendingWorkspaceCreation,
  item: PendingWorkspaceCreationItem,
) {
  return pending.idempotencyKey === item.idempotencyKey
    && pending.createdAt === item.createdAt
    && pending.normalizedPayload === normalizePayload(item.request)
}

function readPendingItem(
  item: PendingWorkspaceCreationItem,
):
  | { status: 'ready'; serialized: string }
  | { status: 'missing' | 'changed' | 'storageUnavailable' } {
  const key = storageKey(item.idempotencyKey)
  try {
    const serialized = window.localStorage.getItem(key)
    if (serialized === null) return { status: 'missing' }
    const parsed: unknown = JSON.parse(serialized)
    if (!isPendingWorkspaceCreation(parsed)
      || storageKey(parsed.idempotencyKey) !== key
      || !pendingMatchesItem(parsed, item)) {
      return { status: 'changed' }
    }
    return { status: 'ready', serialized }
  } catch {
    return { status: 'storageUnavailable' }
  }
}

function legacyPendingCreations() {
  const pendingByPayload = new Map<string, Omit<PendingWorkspaceCreation, 'createdAt'>>()
  try {
    const legacySingleValue = window.localStorage.getItem(LEGACY_SINGLE_STORAGE_KEY)
    if (legacySingleValue !== null) {
      try {
        const parsed: unknown = JSON.parse(legacySingleValue)
        if (isLegacyPendingCreation(parsed)) pendingByPayload.set(parsed.normalizedPayload, parsed)
      } catch {
        // Malformed pre-release data is ignored.
      }
    }

    const legacyCollectionValue = window.localStorage.getItem(LEGACY_COLLECTION_STORAGE_KEY)
    if (legacyCollectionValue !== null) {
      try {
        const parsed = JSON.parse(legacyCollectionValue) as { version?: unknown; entries?: unknown }
        if (parsed?.version === LEGACY_COLLECTION_VERSION && Array.isArray(parsed.entries)) {
          parsed.entries.filter(isLegacyPendingCreation).forEach((pending) => {
            pendingByPayload.set(pending.normalizedPayload, pending)
          })
        }
      } catch {
        // Malformed pre-release data is ignored.
      }
    }
    return [...pendingByPayload.values()]
  } catch {
    return null
  }
}

function migrateLegacyPendingCreations(existing: LocatedPendingCreation[]) {
  const legacy = legacyPendingCreations()
  if (legacy === null) return false
  if (!legacy.length) return true

  const existingLegacy = existing.filter(({ pending }) =>
    parseNormalizedPayload(pending.normalizedPayload).mode === 'legacy')
  const existingPayloads = new Set(
    existingLegacy.map(({ pending }) => pending.normalizedPayload),
  )
  const availableSlots = Math.max(0, MAX_PENDING_CREATIONS - existingLegacy.length)
  const toMigrate = legacy.filter((pending) => !existingPayloads.has(pending.normalizedPayload)).slice(0, availableSlots)
  const migrationStartedAt = Date.now()
  const migrated = toMigrate.every((pending, index) => writePendingCreation({
    ...pending,
    createdAt: migrationStartedAt + index,
  }))
  if (!migrated || toMigrate.length !== legacy.filter((pending) => !existingPayloads.has(pending.normalizedPayload)).length) {
    return migrated
  }

  try {
    window.localStorage.removeItem(LEGACY_SINGLE_STORAGE_KEY)
    window.localStorage.removeItem(LEGACY_COLLECTION_STORAGE_KEY)
  } catch {
    // Verified v3 records remain authoritative if legacy cleanup is unavailable.
  }
  return true
}

function earliestForPayload(entries: LocatedPendingCreation[], normalizedPayload: string) {
  return entries
    .filter(({ pending }) => pending.normalizedPayload === normalizedPayload)
    .sort((left, right) => left.pending.createdAt - right.pending.createdAt
    || left.storageKey.localeCompare(right.storageKey))[0]
}

function contextMatches(
  pending: PendingWorkspaceCreation,
  request: WorkspaceCreationIntent,
) {
  const stored = parseNormalizedPayload(pending.normalizedPayload)
  if (request.mode === 'legacy') return stored.mode === 'legacy'
  return stored.mode === 'session'
    && stored.expectedAccountId === request.expectedAccountId
}

export function listPendingWorkspaceCreations(
  activeAccountId = '',
): PendingWorkspaceCreationListResult {
  let pendingCreations = readPendingCreations()
  if (pendingCreations === null) return { status: 'unavailable' }
  if (!migrateLegacyPendingCreations(pendingCreations)) return { status: 'unavailable' }
  pendingCreations = readPendingCreations()
  if (pendingCreations === null) return { status: 'unavailable' }

  const items = pendingCreations
    .sort((left, right) => left.pending.createdAt - right.pending.createdAt
      || left.storageKey.localeCompare(right.storageKey))
    .map(({ pending }) => ({
      request: parseNormalizedPayload(pending.normalizedPayload),
      idempotencyKey: pending.idempotencyKey,
      createdAt: pending.createdAt,
    }))
    .filter(({ request }) => activeAccountId
      ? request.mode === 'session' && request.expectedAccountId === activeAccountId
      : request.mode === 'legacy')
  return { status: 'ready', items }
}

export function subscribePendingWorkspaceCreations(onChange: () => void) {
  const handleStorage = (event: StorageEvent) => {
    if (event.key === null
      || event.key.startsWith(PENDING_CREATION_STORAGE_PREFIX)
      || event.key === LEGACY_SINGLE_STORAGE_KEY
      || event.key === LEGACY_COLLECTION_STORAGE_KEY) {
      onChange()
    }
  }
  window.addEventListener('storage', handleStorage)
  return () => window.removeEventListener('storage', handleStorage)
}

export function isPendingWorkspaceCreationRequest(
  item: PendingWorkspaceCreationItem,
  request: WorkspaceCreationIntent,
) {
  return isSameWorkspaceCreationRequest(item.request, request)
}

export function isSamePendingWorkspaceCreationItem(
  left: PendingWorkspaceCreationItem,
  right: PendingWorkspaceCreationItem,
) {
  return left.idempotencyKey === right.idempotencyKey
    && left.createdAt === right.createdAt
    && isSameWorkspaceCreationRequest(left.request, right.request)
}

export function isSameWorkspaceCreationRequest(
  left: WorkspaceCreationIntent,
  right: WorkspaceCreationIntent,
) {
  return normalizePayload(left) === normalizePayload(right)
}

export function prepareWorkspaceCreation(
  request: WorkspaceCreationIntent,
): WorkspaceCreationPreparation {
  const normalizedPayload = normalizePayload(request)
  let pendingCreations = readPendingCreations()
  if (pendingCreations === null) return { status: 'blocked', reason: 'storageUnavailable' }

  const existing = earliestForPayload(pendingCreations, normalizedPayload)
  if (existing) return { status: 'ready', idempotencyKey: existing.pending.idempotencyKey }

  if (!migrateLegacyPendingCreations(pendingCreations)) {
    return { status: 'blocked', reason: 'storageUnavailable' }
  }
  pendingCreations = readPendingCreations()
  if (pendingCreations === null) return { status: 'blocked', reason: 'storageUnavailable' }
  const migrated = earliestForPayload(pendingCreations, normalizedPayload)
  if (migrated) return { status: 'ready', idempotencyKey: migrated.pending.idempotencyKey }
  const scopedPendingCreations = pendingCreations.filter(({ pending }) =>
    contextMatches(pending, request))
  if (scopedPendingCreations.length >= MAX_PENDING_CREATIONS) {
    return { status: 'blocked', reason: 'pendingLimitReached' }
  }

  const next: PendingWorkspaceCreation = {
    normalizedPayload,
    idempotencyKey: generateIdempotencyKey(),
    createdAt: Date.now(),
  }
  if (!writePendingCreation(next)) return { status: 'blocked', reason: 'storageUnavailable' }

  const afterWrite = readPendingCreations()
  if (afterWrite === null) return { status: 'ready', idempotencyKey: next.idempotencyKey }
  const coalesced = earliestForPayload(afterWrite, normalizedPayload)
  if (coalesced && coalesced.storageKey !== storageKey(next.idempotencyKey)) {
    removePendingCreation(storageKey(next.idempotencyKey))
    return { status: 'ready', idempotencyKey: coalesced.pending.idempotencyKey }
  }
  const scopedAfterWrite = afterWrite.filter(({ pending }) =>
    contextMatches(pending, request))
  if (scopedAfterWrite.length > MAX_PENDING_CREATIONS
    && removePendingCreation(storageKey(next.idempotencyKey))) {
    return { status: 'blocked', reason: 'pendingLimitReached' }
  }
  return { status: 'ready', idempotencyKey: next.idempotencyKey }
}

export function preparePendingWorkspaceRecovery(
  item: PendingWorkspaceCreationItem,
): PendingWorkspaceRecoveryPreparation {
  const pending = readPendingItem(item)
  if (pending.status !== 'ready') return { status: 'blocked', reason: pending.status }
  return { status: 'ready', idempotencyKey: item.idempotencyKey }
}

export async function runWithWorkspaceCreationLock<Value>(
  operation: () => Promise<Value>,
): Promise<WorkspaceCreationLockResult<Value>> {
  const lockManager = browserLockManager()
  if (!lockManager) return { status: 'unsupported' }

  try {
    return await lockManager.request(
      WORKSPACE_CREATION_LOCK_NAME,
      { ifAvailable: true },
      async (lock) => {
        if (!lock) return { status: 'busy' }
        return { status: 'completed', value: await operation() }
      },
    )
  } catch {
    return { status: 'unsupported' }
  }
}

export async function discardPendingWorkspaceCreation(
  item: PendingWorkspaceCreationItem,
): Promise<PendingWorkspaceDiscardResult> {
  const lockManager = browserLockManager()
  if (!lockManager) return 'unsupported'

  try {
    return await lockManager.request(
      WORKSPACE_CREATION_LOCK_NAME,
      { ifAvailable: true },
      (lock) => {
        if (!lock) return 'busy'
        const pending = readPendingItem(item)
        if (pending.status !== 'ready') return pending.status
        const key = storageKey(item.idempotencyKey)
        try {
          if (window.localStorage.getItem(key) !== pending.serialized) return 'changed'
        } catch {
          return 'storageUnavailable'
        }
        return removePendingCreation(key) ? 'discarded' : 'storageUnavailable'
      },
    )
  } catch {
    return 'unsupported'
  }
}

export function clearPendingWorkspaceCreation(
  request: WorkspaceCreationIntent,
  idempotencyKey: string,
) {
  const key = storageKey(idempotencyKey)
  return clearMatchingVerifiedJsonItem(
    key,
    isPendingWorkspaceCreation,
    (pending) => pending.idempotencyKey === idempotencyKey
      && pending.normalizedPayload === normalizePayload(request),
  )
}
