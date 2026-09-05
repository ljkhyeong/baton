import type {
  AcceptRoleHandoffResponse,
  CancelRoleHandoffResponse,
  CreateNextSeasonResponse,
  Decision,
  HandoffItem,
  Member,
  PrepareRoleHandoffResponse,
  Role,
  RoleResource,
  Routine,
  RoutineExecution,
  SeasonRound,
  SeasonSummary,
  TransferRoleHandoffResponse,
  WorkspaceProjection,
} from './types'
import {
  isInstant,
  isJsonObject as isRecord,
  isNullableInstant,
  isSameUuid,
  isUuid,
} from '@/shared/api/responseValidation'
import { isCalendarDate } from '@/shared/lib/calendarDate'

const LOCAL_TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/

function isNullableString(value: unknown) {
  return value === null || typeof value === 'string'
}

function isNullableUuid(value: unknown) {
  return value === null || isUuid(value)
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null || isNumber(value)
}

function isNullableCalendarDate(value: unknown): value is string | null {
  return value === null || isCalendarDate(value)
}

function isStringArray(value: unknown) {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isUuidArray(value: unknown) {
  return Array.isArray(value) && value.every(isUuid)
}

function isArrayOf(value: unknown, predicate: (item: unknown) => boolean) {
  return Array.isArray(value) && value.every(predicate)
}

function isOneOf(value: unknown, candidates: readonly string[]) {
  return typeof value === 'string' && candidates.includes(value)
}

function hasStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => typeof value[field] === 'string')
}

function hasUuidFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => isUuid(value[field]))
}

function hasNullableUuidFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => isNullableUuid(value[field]))
}

function isSupportedTimeZone(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    new Intl.DateTimeFormat('en-US', { timeZone: value }).format()
    return true
  } catch {
    return false
  }
}

function isRoundSchedule(value: unknown) {
  if (!isRecord(value)) return false

  return isCalendarDate(value.firstMeetingDate)
    && typeof value.meetingTime === 'string'
    && LOCAL_TIME_PATTERN.test(value.meetingTime)
    && isCalendarDate(value.nextOccurrenceDate)
    && isOneOf(value.recurrence, ['WEEKLY', 'BIWEEKLY'])
    && typeof value.enabled === 'boolean'
    && isNumber(value.generationLeadDays)
}

function isNullableLocalTime(value: unknown) {
  return value === null
    || (typeof value === 'string' && LOCAL_TIME_PATTERN.test(value))
}

function isSeasonSummary(value: unknown): value is SeasonSummary {
  if (!isRecord(value)) return false

  return isUuid(value.id)
    && typeof value.name === 'string'
    && isCalendarDate(value.startDate)
    && isCalendarDate(value.endDate)
    && value.startDate <= value.endDate
    && isSupportedTimeZone(value.timeZone)
    && isNullableInstant(value.endedAt)
    && isNullableUuid(value.previousSeasonId)
    && (value.roundSchedule === null || isRoundSchedule(value.roundSchedule))
}

function isTeamSummary(value: unknown) {
  return isRecord(value)
    && isUuid(value.id)
    && typeof value.name === 'string'
}

function isContinuitySignal(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'reason',
    'recommendedAction',
    'title',
  ])
    && isUuid(value.roleId)
    && isNullableCalendarDate(value.relevantDate)
    && isNullableUuid(value.routineId)
    && isOneOf(value.severity, ['CRITICAL', 'WARNING'])
    && isOneOf(value.type, [
      'ROLE_UNASSIGNED',
      'ROLE_SUCCESSOR_MISSING',
      'ROLE_PREPARATION_INCOMPLETE',
      'ROUTINE_REPEATEDLY_OVERDUE',
      'HANDOFF_INCOMPLETE',
    ])
}

function isDecision(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'alternative',
    'authorName',
    'reason',
    'title',
  ])
    && isInstant(value.createdAt)
    && isNullableInstant(value.archivedAt)
    && hasUuidFields(value, ['authorMemberId', 'id'])
    && isUuidArray(value.roleIds)
}

function isHandoffItem(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['label'])
    && typeof value.completed === 'boolean'
    && isNullableInstant(value.archivedAt)
    && isNullableInstant(value.createdAt)
    && hasUuidFields(value, ['id', 'roleId'])
    && isOneOf(value.category, ['RESPONSIBILITY', 'ROUTINE', 'RESOURCE', 'ADVICE'])
}

function isMember(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['initials', 'name', 'tone'])
    && isNullableInstant(value.deactivatedAt)
    && isUuid(value.id)
}

function isRoleResource(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['title', 'url'])
    && isNullableInstant(value.archivedAt)
    && isNullableInstant(value.createdAt)
    && isNullableString(value.description)
    && hasUuidFields(value, ['id', 'roleId'])
}

function isRoleHandoff(
  value: unknown,
): value is WorkspaceProjection['roleHandoffs'][number] {
  if (!isRecord(value)) return false

  return isInstant(value.preparedAt)
    && isNullableInstant(value.acceptedAt)
    && isNullableInstant(value.cancelledAt)
    && isNullableInstant(value.transferredAt)
    && hasUuidFields(value, ['fromMemberId', 'id', 'roleId', 'toMemberId'])
    && hasNullableUuidFields(value, [
      'acceptedByMemberId',
      'cancelledByMemberId',
      'transferredByMemberId',
    ])
    && isCalendarDate(value.outgoingAssignmentStartDate)
    && isNullableCalendarDate(value.outgoingAssignmentEndDate)
    && isCalendarDate(value.incomingAssignmentStartDate)
    && isNullableCalendarDate(value.incomingAssignmentEndDate)
    && isNullableNumber(value.activeItemCount)
    && isNullableNumber(value.incompleteItemCount)
    && isNullableNumber(value.resourceCount)
    && isOneOf(value.status, ['PREPARING', 'TRANSFERRED', 'ACCEPTED', 'CANCELLED'])
    && typeof value.warningAcknowledged === 'boolean'
}

function isRole(value: unknown): value is Role {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['name', 'purpose'])
    && isUuid(value.id)
    && hasNullableUuidFields(value, ['currentMemberId', 'nextMemberId'])
    && isNullableUuid(value.previousRoleId)
    && (value.previousRoleId === null || !isSameUuid(value.previousRoleId, value.id))
    && isNullableCalendarDate(value.assignmentStartDate)
    && isNullableCalendarDate(value.assignmentEndDate)
    && isNullableString(value.risk)
    && isStringArray(value.responsibilities)
}

function isRoutineExecution(value: unknown): value is RoutineExecution {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'detail',
    'dueLabel',
    'title',
  ])
    && isNullableInstant(value.deadlineAt)
    && hasUuidFields(value, ['id', 'ownerRoleId', 'roundId', 'routineId'])
    && isOneOf(value.phase, ['BEFORE', 'DURING', 'AFTER'])
    && isOneOf(value.status, ['WAITING', 'DONE'])
    && isOneOf(value.timingStatus, [
      'UNSCHEDULED',
      'PLANNED',
      'IN_PROGRESS',
      'OVERDUE',
      'COMPLETED',
    ])
}

function isRoutineExecutionArray(
  value: unknown,
): value is SeasonRound['routineExecutions'] {
  return Array.isArray(value) && value.every(isRoutineExecution)
}

function isSeasonRound(value: unknown): value is SeasonRound {
  if (!isRecord(value)) return false

  const hasValidShape = typeof value.name === 'string'
    && isNullableInstant(value.archivedAt)
    && isNullableCalendarDate(value.meetingDate)
    && isNullableInstant(value.scheduledAt)
    && isNullableCalendarDate(value.scheduledOccurrenceDate)
    && isUuid(value.id)
    && isOneOf(value.origin, ['MANUAL', 'AUTOMATIC'])
    && isOneOf(value.timingStatus, ['PLANNED', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED'])

  if (!hasValidShape || !isRoutineExecutionArray(value.routineExecutions)) return false
  return value.routineExecutions.every((execution) => isSameUuid(execution.roundId, value.id))
}

function isRoutine(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'detail',
    'dueLabel',
    'title',
  ])
    && isNullableInstant(value.archivedAt)
    && isNullableNumber(value.deadlineDayOffset)
    && isNullableLocalTime(value.deadlineTime)
    && hasUuidFields(value, ['id', 'ownerRoleId'])
    && isOneOf(value.phase, ['BEFORE', 'DURING', 'AFTER'])
}

function isCopiedRole(value: unknown) {
  return isRecord(value) && hasUuidFields(value, ['roleId', 'sourceRoleId'])
}

function isCopiedRoutine(value: unknown) {
  return isRecord(value) && hasUuidFields(value, ['routineId', 'sourceRoutineId'])
}

function isCreateNextSeasonResponse(value: unknown, sourceSeasonId: string) {
  return isRecord(value)
    && isSeasonSummary(value.sourceSeason)
    && isInstant(value.sourceSeason.endedAt)
    && isSeasonSummary(value.season)
    && isUuid(value.season.previousSeasonId)
    && isSameUuid(value.season.previousSeasonId, value.sourceSeason.id)
    && isSameUuid(value.sourceSeason.id, sourceSeasonId)
    && isArrayOf(value.copiedRoles, isCopiedRole)
    && isArrayOf(value.copiedRoutines, isCopiedRoutine)
}

function isRoleHandoffTransitionResponse(
  value: unknown,
  scope: { roleId: string; handoffId?: string },
) {
  return isRecord(value)
    && isRole(value.role)
    && isRoleHandoff(value.handoff)
    && isSameUuid(value.role.id, value.handoff.roleId)
    && isSameUuid(value.role.id, scope.roleId)
    && (scope.handoffId === undefined || isSameUuid(value.handoff.id, scope.handoffId))
}

function decodeRequiredShape<T>(
  value: unknown,
  predicate: (candidate: unknown) => boolean,
  responseName: string,
): T {
  if (!predicate(value)) {
    throw new TypeError(`${responseName} does not match its required response shape.`)
  }

  return value as T
}

function decodeForExpectedId<T extends { id: string }>(
  value: unknown,
  predicate: (candidate: unknown) => boolean,
  responseName: string,
  expectedId?: string,
): T {
  const decoded = decodeRequiredShape<T>(value, predicate, responseName)
  if (expectedId !== undefined && !isSameUuid(decoded.id, expectedId)) {
    throw new TypeError(`${responseName} does not match its requested target.`)
  }
  return decoded
}

export function decodeSeasonSummary(value: unknown, expectedId?: string): SeasonSummary {
  return decodeForExpectedId<SeasonSummary>(value, isSeasonSummary, 'Season summary', expectedId)
}

export function decodeCreateNextSeasonResponse(
  value: unknown,
  sourceSeasonId: string,
): CreateNextSeasonResponse {
  return decodeRequiredShape(
    value,
    (candidate) => isCreateNextSeasonResponse(candidate, sourceSeasonId),
    'Next season creation response',
  )
}

export function decodeMember(value: unknown, expectedId?: string): Member {
  return decodeForExpectedId<Member>(value, isMember, 'Member response', expectedId)
}

export function decodeRole(value: unknown, expectedId?: string): Role {
  return decodeForExpectedId<Role>(value, isRole, 'Role response', expectedId)
}

function decodeRoleHandoffTransitionResponse<T>(
  value: unknown,
  scope: { roleId: string; handoffId?: string },
): T {
  return decodeRequiredShape<T>(
    value,
    (candidate) => isRoleHandoffTransitionResponse(candidate, scope),
    'Role handoff response',
  )
}

export function decodePrepareRoleHandoffResponse(
  value: unknown,
  roleId: string,
): PrepareRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, { roleId })
}

export function decodeTransferRoleHandoffResponse(
  value: unknown,
  roleId: string,
  handoffId: string,
): TransferRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, { roleId, handoffId })
}

export function decodeAcceptRoleHandoffResponse(
  value: unknown,
  roleId: string,
  handoffId: string,
): AcceptRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, { roleId, handoffId })
}

export function decodeCancelRoleHandoffResponse(
  value: unknown,
  roleId: string,
  handoffId: string,
): CancelRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, { roleId, handoffId })
}

export function decodeRoutine(value: unknown, expectedId?: string): Routine {
  return decodeForExpectedId<Routine>(value, isRoutine, 'Routine response', expectedId)
}

export function decodeSeasonRound(value: unknown, expectedId?: string): SeasonRound {
  return decodeForExpectedId<SeasonRound>(value, isSeasonRound, 'Season round response', expectedId)
}

export function decodeRoutineExecution(
  value: unknown,
  expected?: { executionId: string; roundId: string },
): RoutineExecution {
  const execution = decodeForExpectedId<RoutineExecution>(
    value,
    isRoutineExecution,
    'Routine execution response',
    expected?.executionId,
  )
  if (expected !== undefined && !isSameUuid(execution.roundId, expected.roundId)) {
    throw new TypeError('Routine execution response does not match its requested round.')
  }
  return execution
}

export function decodeDecision(value: unknown, expectedId?: string): Decision {
  return decodeForExpectedId<Decision>(value, isDecision, 'Decision response', expectedId)
}

export function decodeHandoffItem(
  value: unknown,
  expected?: { itemId?: string; roleId?: string },
): HandoffItem {
  const item = decodeForExpectedId<HandoffItem>(
    value,
    isHandoffItem,
    'Handoff item response',
    expected?.itemId,
  )
  if (expected?.roleId !== undefined && !isSameUuid(item.roleId, expected.roleId)) {
    throw new TypeError('Handoff item response does not match its requested role.')
  }
  return item
}

export function decodeRoleResource(
  value: unknown,
  expected?: { resourceId?: string; roleId?: string },
): RoleResource {
  const resource = decodeForExpectedId<RoleResource>(
    value,
    isRoleResource,
    'Role resource response',
    expected?.resourceId,
  )
  if (expected?.roleId !== undefined && !isSameUuid(resource.roleId, expected.roleId)) {
    throw new TypeError('Role resource response does not match its requested role.')
  }
  return resource
}

function decodeWorkspaceProjection(value: unknown): WorkspaceProjection {
  return decodeRequiredShape(value, (candidate) => isRecord(candidate)
    && isTeamSummary(candidate.team)
    && isSeasonSummary(candidate.season)
    && isArrayOf(candidate.seasons, isSeasonSummary)
    && isArrayOf(candidate.continuitySignals, isContinuitySignal)
    && isArrayOf(candidate.decisions, isDecision)
    && isArrayOf(candidate.handoffItems, isHandoffItem)
    && isArrayOf(candidate.members, isMember)
    && isArrayOf(candidate.resources, isRoleResource)
    && isArrayOf(candidate.roleHandoffs, isRoleHandoff)
    && isArrayOf(candidate.roles, isRole)
    && isArrayOf(candidate.rounds, isSeasonRound)
    && isArrayOf(candidate.routines, isRoutine), 'Workspace projection')
}

function hasValidContinuitySignalTargets(projection: WorkspaceProjection) {
  const roleIds = new Set(projection.roles.map((role) => role.id.toLowerCase()))
  const routinesById = new Map(
    projection.routines.map((routine) => [routine.id.toLowerCase(), routine]),
  )

  return projection.continuitySignals.every((signal) => {
    if (!roleIds.has(signal.roleId.toLowerCase())) return false
    if (signal.type !== 'ROUTINE_REPEATEDLY_OVERDUE') {
      return signal.routineId === null
    }
    if (signal.routineId === null) return false
    const routine = routinesById.get(signal.routineId.toLowerCase())
    return routine !== undefined && isSameUuid(routine.ownerRoleId, signal.roleId)
  })
}

export function decodeWorkspaceProjectionForScope(
  value: unknown,
  scope: { teamId: string; seasonId: string },
): WorkspaceProjection {
  const projection = decodeWorkspaceProjection(value)

  if (!isSameUuid(projection.team.id, scope.teamId)
    || !isSameUuid(projection.season.id, scope.seasonId)) {
    throw new TypeError('Workspace projection does not match its requested scope.')
  }

  const currentSeasonCount = projection.seasons.filter((season) => (
    isSameUuid(season.id, projection.season.id)
  )).length
  if (currentSeasonCount !== 1) {
    throw new TypeError('Workspace projection must contain its current season exactly once.')
  }
  if (!hasValidContinuitySignalTargets(projection)) {
    throw new TypeError('Workspace projection contains an invalid continuity signal target.')
  }
  if (projection.roles.some((role) => role.previousRoleId)
    && projection.season.previousSeasonId
    && (isSameUuid(projection.season.previousSeasonId, projection.season.id)
      || !projection.seasons.some((season) => isSameUuid(season.id, projection.season.previousSeasonId)))) {
    throw new TypeError('Workspace projection contains a role without its previous season.')
  }

  return projection
}
