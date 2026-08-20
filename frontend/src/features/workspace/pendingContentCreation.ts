import {
  clearMatchingJsonItem,
  isJsonCleanupComplete,
  readValidatedJson,
  scanValidatedJson,
  writeJson,
} from '@/shared/lib/durableStorage'
import type { JsonCleanupResult } from '@/shared/lib/durableStorage'
import { runWithBrowserLock } from '@/shared/lib/browserLock'
import type { BrowserLockResult } from '@/shared/lib/browserLock'
import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'
import type { WorkspaceScope } from './api'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateMemberRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  PrepareRoleHandoffCommandRequest,
} from './types'

const STORAGE_PREFIX = 'baton-pending-content-creation:v1:'
const CLEANUP_MARKER_STORAGE_KEY = 'baton-content-creation-cleanup-required:v1'
const MAX_PENDING_CREATIONS = 20
const CONTENT_CREATION_LOCK_NAME = 'baton-content-creation'

export type ContentCreationRequestByOperation = {
  member: CreateMemberRequest
  role: CreateRoleRequest
  routine: CreateRoutineRequest
  round: CreateSeasonRoundRequest
  decision: CreateDecisionRequest
  handoffItem: CreateHandoffItemRequest
  roleResource: CreateRoleResourceRequest
  roleHandoff: PrepareRoleHandoffCommandRequest
}
export type ContentCreationOperation = keyof ContentCreationRequestByOperation
type ContentCreationPreparation =
  | { status: 'ready'; idempotencyKey: string }
  | {
      status: 'blocked'
      reason:
        | 'storageUnavailable'
        | 'pendingLimitReached'
        | 'cleanupRequired'
        | 'guardedRequestPending'
    }
type ContentCreationLockResult<Value> = BrowserLockResult<Value>

type ContentCreationRequest =
  ContentCreationRequestByOperation[ContentCreationOperation]

type PendingContentCreation = {
  teamId: string
  seasonId: string
  operation: ContentCreationOperation
  normalizedPayload: string
  idempotencyKey: string
  createdAt: number
  requestGuard?: true
  cleanupRequired?: true
}

export type PendingContentCreationCleanupRetry = Pick<
  PendingContentCreation,
  'teamId' | 'seasonId' | 'operation' | 'normalizedPayload' | 'idempotencyKey'
>

type LocatedPendingContentCreation = {
  storageKey: string
  pending: PendingContentCreation
}

type WorkspaceIdentity = Pick<WorkspaceScope, 'teamId' | 'seasonId'>

type CleanupChangeListener = () => void

const cleanupChangeListeners = new Set<CleanupChangeListener>()
let volatileCleanupRetry: PendingContentCreationCleanupRetry | null = null

function notifyCleanupChange() {
  cleanupChangeListeners.forEach((listener) => listener())
}

export function subscribePendingContentCreationCleanup(listener: CleanupChangeListener) {
  const handleStorage = (event: StorageEvent) => {
    if (event.key === CLEANUP_MARKER_STORAGE_KEY
      || event.key?.startsWith(STORAGE_PREFIX)) listener()
  }
  cleanupChangeListeners.add(listener)
  window.addEventListener('storage', handleStorage)
  return () => {
    cleanupChangeListeners.delete(listener)
    window.removeEventListener('storage', handleStorage)
  }
}

function trimNullable(value: string | null) {
  if (value === null) return null
  const trimmed = value.trim()
  return trimmed || null
}

function normalizePayload<Operation extends ContentCreationOperation>(
  operation: Operation,
  request: ContentCreationRequestByOperation[Operation],
): string {
  switch (operation) {
    case 'member': {
      const member = request as CreateMemberRequest
      return JSON.stringify({
        name: member.name.trim(),
      })
    }
    case 'role': {
      const role = request as CreateRoleRequest
      return JSON.stringify({
        name: role.name.trim(),
        purpose: role.purpose.trim(),
        currentMemberId: trimNullable(role.currentMemberId),
        nextMemberId: trimNullable(role.nextMemberId),
        assignmentStartDate: trimNullable(role.assignmentStartDate),
        assignmentEndDate: trimNullable(role.assignmentEndDate),
        responsibilities: role.responsibilities.map((responsibility) => responsibility.trim()),
        risk: trimNullable(role.risk),
      })
    }
    case 'routine': {
      const routine = request as CreateRoutineRequest
      return JSON.stringify({
        title: routine.title.trim(),
        phase: routine.phase,
        dueLabel: routine.dueLabel.trim(),
        deadlineDayOffset: routine.deadlineDayOffset ?? null,
        deadlineTime: routine.deadlineDayOffset == null
          ? null
          : trimNullable(routine.deadlineTime ?? null),
        ownerRoleId: routine.ownerRoleId.trim(),
        detail: routine.detail.trim(),
      })
    }
    case 'round': {
      const round = request as CreateSeasonRoundRequest
      return JSON.stringify({
        name: round.name.trim(),
        meetingDate: round.meetingDate.trim(),
      })
    }
    case 'decision': {
      const decision = request as CreateDecisionRequest
      return JSON.stringify({
        title: decision.title.trim(),
        reason: decision.reason.trim(),
        alternative: decision.alternative.trim(),
        authorMemberId: decision.authorMemberId.trim(),
        roleIds: decision.roleIds.map((roleId) => roleId.trim()),
      })
    }
    case 'handoffItem': {
      const item = request as CreateHandoffItemRequest
      return JSON.stringify({
        roleId: item.roleId.trim(),
        label: item.label.trim(),
        category: item.category,
      })
    }
    case 'roleResource': {
      const resource = request as CreateRoleResourceRequest
      return JSON.stringify({
        roleId: resource.roleId.trim(),
        title: resource.title.trim(),
        url: resource.url.trim(),
        description: trimNullable(resource.description ?? null),
      })
    }
    case 'roleHandoff': {
      const handoff = request as PrepareRoleHandoffCommandRequest
      return JSON.stringify({
        roleId: handoff.roleId.trim(),
        toMemberId: handoff.toMemberId.trim(),
        incomingAssignmentStartDate: handoff.incomingAssignmentStartDate.trim(),
        incomingAssignmentEndDate: trimNullable(
          handoff.incomingAssignmentEndDate ?? null,
        ),
      })
    }
    default:
      throw new Error(`지원하지 않는 콘텐츠 생성 작업입니다: ${String(operation)}`)
  }
}

function storageKey(idempotencyKey: string) {
  return `${STORAGE_PREFIX}${idempotencyKey}`
}

function isOperation(value: unknown): value is ContentCreationOperation {
  return value === 'member' || value === 'role' || value === 'routine' || value === 'round'
    || value === 'decision' || value === 'handoffItem' || value === 'roleResource'
    || value === 'roleHandoff'
}

function isNormalizedPayload(operation: ContentCreationOperation, value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const parsed = JSON.parse(value) as ContentCreationRequest
    if (!parsed || typeof parsed !== 'object') return false
    if (operation === 'routine') {
      const phase = (parsed as Partial<CreateRoutineRequest>).phase
      if (phase !== 'BEFORE' && phase !== 'DURING' && phase !== 'AFTER') return false
    }
    if (operation === 'handoffItem') {
      const category = (parsed as Partial<CreateHandoffItemRequest>).category
      if (category !== 'RESPONSIBILITY' && category !== 'ROUTINE'
        && category !== 'RESOURCE' && category !== 'ADVICE') return false
    }
    return normalizePayload(operation, parsed) === value
  } catch {
    return false
  }
}

function isPendingContentCreation(value: unknown): value is PendingContentCreation {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<PendingContentCreation>
  return typeof candidate.teamId === 'string'
    && Boolean(candidate.teamId)
    && typeof candidate.seasonId === 'string'
    && Boolean(candidate.seasonId)
    && isOperation(candidate.operation)
    && isNormalizedPayload(candidate.operation, candidate.normalizedPayload)
    && isValidIdempotencyKey(candidate.idempotencyKey)
    && typeof candidate.createdAt === 'number'
    && Number.isFinite(candidate.createdAt)
    && candidate.createdAt >= 0
    && (candidate.requestGuard === undefined || candidate.requestGuard === true)
    && (candidate.cleanupRequired === undefined || candidate.cleanupRequired === true)
}

function readPendingContentCreations(): LocatedPendingContentCreation[] | null {
  const entries = scanValidatedJson(
    STORAGE_PREFIX,
    (value, key): value is PendingContentCreation =>
      isPendingContentCreation(value) && storageKey(value.idempotencyKey) === key,
  )
  return entries?.map(({ storageKey, value: pending }) => ({ storageKey, pending })) ?? null
}

function writePendingContentCreation(pending: PendingContentCreation) {
  return writeJson(storageKey(pending.idempotencyKey), pending)
}

function cleanupRetry(pending: PendingContentCreation): PendingContentCreationCleanupRetry {
  return {
    teamId: pending.teamId,
    seasonId: pending.seasonId,
    operation: pending.operation,
    normalizedPayload: pending.normalizedPayload,
    idempotencyKey: pending.idempotencyKey,
  }
}

function sameCleanupRetry(
  pending: PendingContentCreation,
  retry: PendingContentCreationCleanupRetry,
) {
  return pending.teamId === retry.teamId
    && pending.seasonId === retry.seasonId
    && pending.operation === retry.operation
    && pending.normalizedPayload === retry.normalizedPayload
    && pending.idempotencyKey === retry.idempotencyKey
}

export function pendingContentCreationCleanupRetry(): PendingContentCreationCleanupRetry | null {
  const marker = readValidatedJson(
    CLEANUP_MARKER_STORAGE_KEY,
    (value): value is PendingContentCreation =>
      isPendingContentCreation(value) && value.cleanupRequired === true,
  )
  if (marker) return cleanupRetry(marker)

  const pendingCreations = readPendingContentCreations()
  const durableRetry = pendingCreations
    ?.filter(({ pending }) => pending.cleanupRequired === true)
    .sort((left, right) => left.pending.createdAt - right.pending.createdAt
      || left.storageKey.localeCompare(right.storageKey))[0]
  return durableRetry ? cleanupRetry(durableRetry.pending) : volatileCleanupRetry
}

export function markPendingContentCreationCleanupRequired<
  Operation extends ContentCreationOperation,
>(
  scope: WorkspaceIdentity,
  operation: Operation,
  request: ContentCreationRequestByOperation[Operation],
  idempotencyKey: string,
): PendingContentCreationCleanupRetry {
  const key = storageKey(idempotencyKey)
  const normalizedPayload = normalizePayload(operation, request)
  const retry: PendingContentCreationCleanupRetry = {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    operation,
    normalizedPayload,
    idempotencyKey,
  }
  volatileCleanupRetry = retry

  const pending = readValidatedJson(key, isPendingContentCreation)
  const matchingPending = pending
    && pending.idempotencyKey === idempotencyKey
    && matches(pending, scope, operation, normalizedPayload)
    ? pending
    : null
  const markedPending: PendingContentCreation = {
    ...retry,
    createdAt: matchingPending?.createdAt ?? Date.now(),
    requestGuard: true,
    cleanupRequired: true,
  }
  const pendingMarked = Boolean(matchingPending
    && writeJson(key, { ...matchingPending, cleanupRequired: true }))
  const durableMarked = pendingMarked
    || writeJson(CLEANUP_MARKER_STORAGE_KEY, markedPending)
  if (durableMarked) {
    volatileCleanupRetry = null
  }
  notifyCleanupChange()
  return retry
}

export function clearPendingContentCreationCleanup(
  retry: PendingContentCreationCleanupRetry,
): JsonCleanupResult {
  const pendingResult = clearMatchingJsonItem(
    storageKey(retry.idempotencyKey),
    isPendingContentCreation,
    (pending) => sameCleanupRetry(pending, retry),
  )
  if (!isJsonCleanupComplete(pendingResult)) {
    notifyCleanupChange()
    return pendingResult
  }

  const markerResult = clearMatchingJsonItem(
    CLEANUP_MARKER_STORAGE_KEY,
    (value): value is PendingContentCreation =>
      isPendingContentCreation(value) && value.cleanupRequired === true,
    (pending) => sameCleanupRetry(pending, retry),
  )
  if (isJsonCleanupComplete(markerResult)) {
    if (volatileCleanupRetry?.idempotencyKey === retry.idempotencyKey) {
      volatileCleanupRetry = null
    }
    notifyCleanupChange()
    return 'cleared'
  }
  notifyCleanupChange()
  return markerResult
}

function matches(
  pending: PendingContentCreation,
  scope: WorkspaceIdentity,
  operation: ContentCreationOperation,
  normalizedPayload?: string,
) {
  return pending.teamId === scope.teamId
    && pending.seasonId === scope.seasonId
    && pending.operation === operation
    && (normalizedPayload === undefined || pending.normalizedPayload === normalizedPayload)
}

function earliestMatch(
  entries: LocatedPendingContentCreation[],
  scope: WorkspaceIdentity,
  operation: ContentCreationOperation,
  normalizedPayload: string,
) {
  return entries
    .filter(({ pending }) => matches(pending, scope, operation, normalizedPayload))
    .sort((left, right) => left.pending.createdAt - right.pending.createdAt
      || left.storageKey.localeCompare(right.storageKey))[0]
}

function earliestGuarded(
  entries: LocatedPendingContentCreation[],
) {
  return entries
    .filter(({ pending }) => pending.requestGuard === true)
    .sort((left, right) => left.pending.createdAt - right.pending.createdAt
      || left.storageKey.localeCompare(right.storageKey))[0]
}

export function prepareContentCreation<Operation extends ContentCreationOperation>(
  scope: WorkspaceIdentity,
  operation: Operation,
  request: ContentCreationRequestByOperation[Operation],
): ContentCreationPreparation {
  if (pendingContentCreationCleanupRetry()) {
    return { status: 'blocked', reason: 'cleanupRequired' }
  }
  const normalizedPayload = normalizePayload(operation, request)
  const pendingCreations = readPendingContentCreations()
  if (pendingCreations === null) return { status: 'blocked', reason: 'storageUnavailable' }

  const guarded = earliestGuarded(pendingCreations)
  if (guarded) {
    return matches(guarded.pending, scope, operation, normalizedPayload)
      ? { status: 'ready', idempotencyKey: guarded.pending.idempotencyKey }
      : { status: 'blocked', reason: 'guardedRequestPending' }
  }

  const existing = earliestMatch(pendingCreations, scope, operation, normalizedPayload)
  if (existing) {
    const upgraded = { ...existing.pending, requestGuard: true as const }
    return writeJson(existing.storageKey, upgraded)
      ? { status: 'ready', idempotencyKey: existing.pending.idempotencyKey }
      : { status: 'blocked', reason: 'storageUnavailable' }
  }
  if (pendingCreations.length >= MAX_PENDING_CREATIONS) {
    return { status: 'blocked', reason: 'pendingLimitReached' }
  }

  const next: PendingContentCreation = {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    operation,
    normalizedPayload,
    idempotencyKey: generateIdempotencyKey(),
    createdAt: Date.now(),
    requestGuard: true,
  }
  if (!writePendingContentCreation(next)) return { status: 'blocked', reason: 'storageUnavailable' }
  return { status: 'ready', idempotencyKey: next.idempotencyKey }
}

export async function runWithContentCreationLock<Value>(
  operation: () => Promise<Value>,
): Promise<ContentCreationLockResult<Value>> {
  return runWithBrowserLock(CONTENT_CREATION_LOCK_NAME, operation)
}

export function hasPendingContentCreation(scope: WorkspaceIdentity, operation: ContentCreationOperation) {
  const pendingCreations = readPendingContentCreations()
  return pendingCreations?.some(({ pending }) =>
    pending.cleanupRequired !== true && matches(pending, scope, operation)) ?? false
}
