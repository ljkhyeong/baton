import type { CreateWorkspaceRequest } from '@/features/workspace/types'
import {
  readValidatedJson,
  removeVerifiedJsonItem,
  scanValidatedJson,
  writeVerifiedJson,
} from '@/shared/lib/durableStorage'
import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'

const PENDING_CREATION_STORAGE_PREFIX = 'baton-pending-workspace-creation:v3:'
const LEGACY_SINGLE_STORAGE_KEY = 'baton-pending-workspace-creation:v1'
const LEGACY_COLLECTION_STORAGE_KEY = 'baton-pending-workspace-creations:v2'
const LEGACY_COLLECTION_VERSION = 2
const MAX_PENDING_CREATIONS = 5

type PendingWorkspaceCreation = {
  normalizedPayload: string
  idempotencyKey: string
  createdAt: number
}

type LocatedPendingCreation = {
  storageKey: string
  pending: PendingWorkspaceCreation
}

function normalizePayload(request: CreateWorkspaceRequest) {
  return JSON.stringify({
    teamName: request.teamName.trim(),
    seasonName: request.seasonName.trim(),
    startDate: request.startDate.trim(),
    endDate: request.endDate.trim(),
    memberNames: request.memberNames.map((name) => name.trim()).sort(),
  })
}

function isNormalizedPayload(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const parsed = JSON.parse(value) as Partial<CreateWorkspaceRequest> | null
    if (!parsed || typeof parsed !== 'object') return false
    if (typeof parsed.teamName !== 'string'
      || typeof parsed.seasonName !== 'string'
      || typeof parsed.startDate !== 'string'
      || typeof parsed.endDate !== 'string'
      || !Array.isArray(parsed.memberNames)
      || !parsed.memberNames.length
      || !parsed.memberNames.every((name) => typeof name === 'string' && Boolean(name) && name === name.trim())
      || new Set(parsed.memberNames).size !== parsed.memberNames.length) {
      return false
    }
    return normalizePayload(parsed as CreateWorkspaceRequest) === value
  } catch {
    return false
  }
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

  const existingPayloads = new Set(existing.map(({ pending }) => pending.normalizedPayload))
  const availableSlots = Math.max(0, MAX_PENDING_CREATIONS - existing.length)
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

export function idempotencyKeyFor(request: CreateWorkspaceRequest): string | null {
  const normalizedPayload = normalizePayload(request)
  let pendingCreations = readPendingCreations()
  if (pendingCreations === null) return null

  const existing = earliestForPayload(pendingCreations, normalizedPayload)
  if (existing) return existing.pending.idempotencyKey

  if (!migrateLegacyPendingCreations(pendingCreations)) return null
  pendingCreations = readPendingCreations()
  if (pendingCreations === null) return null
  const migrated = earliestForPayload(pendingCreations, normalizedPayload)
  if (migrated) return migrated.pending.idempotencyKey
  if (pendingCreations.length >= MAX_PENDING_CREATIONS) return null

  const next: PendingWorkspaceCreation = {
    normalizedPayload,
    idempotencyKey: generateIdempotencyKey(),
    createdAt: Date.now(),
  }
  if (!writePendingCreation(next)) return null

  const afterWrite = readPendingCreations()
  if (afterWrite === null) return next.idempotencyKey
  const coalesced = earliestForPayload(afterWrite, normalizedPayload)
  if (coalesced && coalesced.storageKey !== storageKey(next.idempotencyKey)) {
    removePendingCreation(storageKey(next.idempotencyKey))
    return coalesced.pending.idempotencyKey
  }
  if (afterWrite.length > MAX_PENDING_CREATIONS && removePendingCreation(storageKey(next.idempotencyKey))) {
    return null
  }
  return next.idempotencyKey
}

export function clearPendingWorkspaceCreation(request: CreateWorkspaceRequest, idempotencyKey: string) {
  const key = storageKey(idempotencyKey)
  try {
    const pending = readValidatedJson(key, isPendingWorkspaceCreation)
    if (!pending
      || pending.idempotencyKey !== idempotencyKey
      || pending.normalizedPayload !== normalizePayload(request)) {
      return
    }
    removePendingCreation(key)
  } catch {
    // A stale pending value is safer than deleting another tab's recoverable entry.
  }
}
