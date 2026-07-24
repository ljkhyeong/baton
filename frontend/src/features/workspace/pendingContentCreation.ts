import { generateIdempotencyKey, isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'
import type { WorkspaceScope } from './api'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
} from './types'

const STORAGE_PREFIX = 'baton-pending-content-creation:v1:'
const MAX_PENDING_CREATIONS = 20

export type ContentCreationRequestByOperation = {
  role: CreateRoleRequest
  routine: CreateRoutineRequest
  round: CreateSeasonRoundRequest
  decision: CreateDecisionRequest
  handoffItem: CreateHandoffItemRequest
  roleResource: CreateRoleResourceRequest
}
export type ContentCreationOperation = keyof ContentCreationRequestByOperation
export type ContentCreationPreparation =
  | { status: 'ready'; idempotencyKey: string }
  | { status: 'blocked'; reason: 'storageUnavailable' | 'pendingLimitReached' }

type ContentCreationRequest =
  ContentCreationRequestByOperation[ContentCreationOperation]

type PendingContentCreation = {
  teamId: string
  seasonId: string
  operation: ContentCreationOperation
  normalizedPayload: string
  idempotencyKey: string
  createdAt: number
}

type LocatedPendingContentCreation = {
  storageKey: string
  pending: PendingContentCreation
}

type WorkspaceIdentity = Pick<WorkspaceScope, 'teamId' | 'seasonId'>

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
    default:
      throw new Error(`지원하지 않는 콘텐츠 생성 작업입니다: ${String(operation)}`)
  }
}

function storageKey(idempotencyKey: string) {
  return `${STORAGE_PREFIX}${idempotencyKey}`
}

function isOperation(value: unknown): value is ContentCreationOperation {
  return value === 'role' || value === 'routine' || value === 'round'
    || value === 'decision' || value === 'handoffItem' || value === 'roleResource'
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
}

function readPendingContentCreations(): LocatedPendingContentCreation[] | null {
  try {
    const located: LocatedPendingContentCreation[] = []
    const invalidKeys: string[] = []
    for (let index = 0; index < window.localStorage.length; index += 1) {
      const key = window.localStorage.key(index)
      if (!key?.startsWith(STORAGE_PREFIX)) continue
      const storedValue = window.localStorage.getItem(key)
      if (storedValue === null) continue
      try {
        const pending: unknown = JSON.parse(storedValue)
        if (isPendingContentCreation(pending) && storageKey(pending.idempotencyKey) === key) {
          located.push({ storageKey: key, pending })
        } else {
          invalidKeys.push(key)
        }
      } catch {
        invalidKeys.push(key)
      }
    }
    invalidKeys.forEach((key) => {
      try {
        window.localStorage.removeItem(key)
      } catch {
        // Invalid records are ignored when browser storage cleanup is unavailable.
      }
    })
    return located
  } catch {
    return null
  }
}

function writePendingContentCreation(pending: PendingContentCreation) {
  const key = storageKey(pending.idempotencyKey)
  const serialized = JSON.stringify(pending)
  try {
    window.localStorage.setItem(key, serialized)
    return window.localStorage.getItem(key) === serialized
  } catch {
    return false
  }
}

function removePendingContentCreation(key: string) {
  try {
    window.localStorage.removeItem(key)
    if (window.localStorage.getItem(key) === null) return true
  } catch {
    // A verified null tombstone is attempted below when direct removal is unavailable.
  }
  try {
    window.localStorage.setItem(key, 'null')
    return window.localStorage.getItem(key) === 'null'
  } catch {
    return false
  }
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

export function prepareContentCreation<Operation extends ContentCreationOperation>(
  scope: WorkspaceIdentity,
  operation: Operation,
  request: ContentCreationRequestByOperation[Operation],
): ContentCreationPreparation {
  const normalizedPayload = normalizePayload(operation, request)
  const pendingCreations = readPendingContentCreations()
  if (pendingCreations === null) return { status: 'blocked', reason: 'storageUnavailable' }

  const existing = earliestMatch(pendingCreations, scope, operation, normalizedPayload)
  if (existing) return { status: 'ready', idempotencyKey: existing.pending.idempotencyKey }
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
  }
  if (!writePendingContentCreation(next)) return { status: 'blocked', reason: 'storageUnavailable' }

  const afterWrite = readPendingContentCreations()
  if (afterWrite === null) return { status: 'blocked', reason: 'storageUnavailable' }
  const coalesced = earliestMatch(afterWrite, scope, operation, normalizedPayload)
  if (coalesced && coalesced.storageKey !== storageKey(next.idempotencyKey)) {
    removePendingContentCreation(storageKey(next.idempotencyKey))
    return { status: 'ready', idempotencyKey: coalesced.pending.idempotencyKey }
  }
  if (afterWrite.length > MAX_PENDING_CREATIONS) {
    removePendingContentCreation(storageKey(next.idempotencyKey))
    return { status: 'blocked', reason: 'pendingLimitReached' }
  }
  return { status: 'ready', idempotencyKey: next.idempotencyKey }
}

export function clearPendingContentCreation<Operation extends ContentCreationOperation>(
  scope: WorkspaceIdentity,
  operation: Operation,
  request: ContentCreationRequestByOperation[Operation],
  idempotencyKey: string,
) {
  const key = storageKey(idempotencyKey)
  try {
    const storedValue = window.localStorage.getItem(key)
    if (storedValue === null) return
    const pending: unknown = JSON.parse(storedValue)
    if (!isPendingContentCreation(pending)
      || pending.idempotencyKey !== idempotencyKey
      || !matches(pending, scope, operation, normalizePayload(operation, request))) {
      return
    }
    removePendingContentCreation(key)
  } catch {
    // A stale pending value is safer than deleting another tab's recoverable entry.
  }
}

export function hasPendingContentCreation(scope: WorkspaceIdentity, operation: ContentCreationOperation) {
  const pendingCreations = readPendingContentCreations()
  return pendingCreations?.some(({ pending }) => matches(pending, scope, operation)) ?? false
}
