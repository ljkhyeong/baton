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
  isNonEmptyString,
  isNullableInstant,
  isUuid,
} from '@/shared/api/responseValidation'

const CALENDAR_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const LOCAL_TIME_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/
type RoleHandoffStatus = WorkspaceProjection['roleHandoffs'][number]['status']

const allRoleHandoffStatuses = [
  'PREPARING',
  'TRANSFERRED',
  'ACCEPTED',
  'CANCELLED',
] as const satisfies readonly RoleHandoffStatus[]
const replayableTransferStatuses = [
  'TRANSFERRED',
  'ACCEPTED',
  'CANCELLED',
] as const satisfies readonly RoleHandoffStatus[]

function isNullableString(value: unknown) {
  return value === null || typeof value === 'string'
}

function isNullableUuid(value: unknown) {
  return value === null || isUuid(value)
}

function hasSameUuid(left: unknown, right: unknown) {
  return isUuid(left)
    && isUuid(right)
    && left.toLowerCase() === right.toLowerCase()
}

function hasSameNullableUuid(left: unknown, right: unknown) {
  return left === null || right === null
    ? left === right
    : hasSameUuid(left, right)
}

function isNullableNonNegativeInteger(value: unknown): value is number | null {
  return value === null
    || (typeof value === 'number' && Number.isInteger(value) && value >= 0)
}

function hasValidRoleHandoffCounts(
  activeItemCount: unknown,
  incompleteItemCount: unknown,
  resourceCount: unknown,
) {
  if (!isNullableNonNegativeInteger(activeItemCount)
    || !isNullableNonNegativeInteger(incompleteItemCount)
    || !isNullableNonNegativeInteger(resourceCount)) {
    return false
  }

  if (activeItemCount === null
    || incompleteItemCount === null
    || resourceCount === null) {
    return activeItemCount === null
      && incompleteItemCount === null
      && resourceCount === null
  }

  return incompleteItemCount <= activeItemCount
}

function isInstantAtOrAfter(value: unknown, lowerBound: unknown) {
  if (!isInstant(value) || !isInstant(lowerBound)) return false

  const normalized = (instant: string) => {
    const withoutZone = instant.slice(0, -1)
    const [dateTime, fraction = ''] = withoutZone.split('.')
    return `${dateTime}.${fraction.padEnd(9, '0')}`
  }

  return normalized(value) >= normalized(lowerBound)
}

function hasTransferredRoleHandoffState(value: Record<string, unknown>) {
  const activeItemCount = value.activeItemCount
  const incompleteItemCount = value.incompleteItemCount
  const resourceCount = value.resourceCount
  if (!isInstant(value.transferredAt)
    || !hasSameUuid(value.transferredByMemberId, value.fromMemberId)
    || !isInstantAtOrAfter(value.transferredAt, value.preparedAt)
    || !isNullableNonNegativeInteger(activeItemCount)
    || !isNullableNonNegativeInteger(incompleteItemCount)
    || !isNullableNonNegativeInteger(resourceCount)
    || activeItemCount === null
    || incompleteItemCount === null
    || resourceCount === null
    || incompleteItemCount > activeItemCount) {
    return false
  }

  const warningsPresent = activeItemCount === 0
    || incompleteItemCount > 0
    || resourceCount === 0
  return !warningsPresent || value.warningAcknowledged === true
}

function hasValidRoleHandoffState(value: Record<string, unknown>) {
  const hasNoTransfer = value.transferredAt === null
    && value.transferredByMemberId === null
    && value.activeItemCount === null
    && value.incompleteItemCount === null
    && value.resourceCount === null
    && value.warningAcknowledged === false
  const hasNoAcceptance = value.acceptedAt === null
    && value.acceptedByMemberId === null
  const hasNoCancellation = value.cancelledAt === null
    && value.cancelledByMemberId === null

  if (value.status === 'PREPARING') {
    return hasNoTransfer && hasNoAcceptance && hasNoCancellation
  }

  if (value.status === 'TRANSFERRED') {
    return hasTransferredRoleHandoffState(value)
      && hasNoAcceptance
      && hasNoCancellation
  }

  if (value.status === 'ACCEPTED') {
    return hasTransferredRoleHandoffState(value)
      && hasSameUuid(value.acceptedByMemberId, value.toMemberId)
      && isInstantAtOrAfter(value.acceptedAt, value.transferredAt)
      && hasNoCancellation
  }

  if (value.status === 'CANCELLED') {
    return hasNoAcceptance
      && hasSameUuid(value.cancelledByMemberId, value.fromMemberId)
      && (hasNoTransfer
        ? isInstantAtOrAfter(value.cancelledAt, value.preparedAt)
        : hasTransferredRoleHandoffState(value)
          && isInstantAtOrAfter(value.cancelledAt, value.transferredAt))
  }

  return false
}

function isCalendarDate(value: unknown): value is string {
  if (typeof value !== 'string') return false

  const match = CALENDAR_DATE_PATTERN.exec(value)
  if (!match) return false

  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const normalized = new Date(0)
  normalized.setUTCHours(0, 0, 0, 0)
  normalized.setUTCFullYear(year, month - 1, day)

  return normalized.getUTCFullYear() === year
    && normalized.getUTCMonth() === month - 1
    && normalized.getUTCDate() === day
}

function isNullableCalendarDate(value: unknown): value is string | null {
  return value === null || isCalendarDate(value)
}

function isOrderedCalendarPeriod(start: unknown, end: unknown) {
  return isNullableCalendarDate(start)
    && isNullableCalendarDate(end)
    && (start === null || end === null || start <= end)
}

function isOrderedCalendarPeriodWithStart(start: unknown, end: unknown) {
  return isCalendarDate(start)
    && isNullableCalendarDate(end)
    && (end === null || start <= end)
}

function isNonEmptyStringArray(value: unknown) {
  return Array.isArray(value) && value.every(isNonEmptyString)
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

function hasNonEmptyStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => isNonEmptyString(value[field]))
}

function hasNullableStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => isNullableString(value[field]))
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

function isSupportedTimeZone(value: unknown) {
  if (!isNonEmptyString(value)) return false

  try {
    new Intl.DateTimeFormat('ko-KR', { timeZone: value }).format()
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
    && typeof value.generationLeadDays === 'number'
    && Number.isInteger(value.generationLeadDays)
    && value.generationLeadDays >= 0
    && value.generationLeadDays <= 30
}

function hasValidRoutineDeadline(deadlineDayOffset: unknown, deadlineTime: unknown) {
  if (deadlineDayOffset === null || deadlineTime === null) {
    return deadlineDayOffset === null && deadlineTime === null
  }

  return typeof deadlineDayOffset === 'number'
    && Number.isInteger(deadlineDayOffset)
    && deadlineDayOffset >= -30
    && deadlineDayOffset <= 30
    && typeof deadlineTime === 'string'
    && LOCAL_TIME_PATTERN.test(deadlineTime)
}

function isSeasonSummary(value: unknown): value is SeasonSummary {
  if (!isRecord(value)) return false

  const { startDate, endDate } = value

  return isUuid(value.id)
    && isNonEmptyString(value.name)
    && isCalendarDate(startDate)
    && isCalendarDate(endDate)
    && startDate <= endDate
    && isSupportedTimeZone(value.timeZone)
    && isNullableInstant(value.endedAt)
    && isNullableUuid(value.previousSeasonId)
    && (value.roundSchedule === null || isRoundSchedule(value.roundSchedule))
}

function hasSameRoundSchedule(
  left: SeasonSummary['roundSchedule'],
  right: SeasonSummary['roundSchedule'],
) {
  if (left === null || right === null) return left === right

  return left.enabled === right.enabled
    && left.firstMeetingDate === right.firstMeetingDate
    && left.generationLeadDays === right.generationLeadDays
    && left.meetingTime === right.meetingTime
    && left.nextOccurrenceDate === right.nextOccurrenceDate
    && left.recurrence === right.recurrence
}

function hasSameSeasonSummary(left: SeasonSummary, right: SeasonSummary) {
  return hasSameUuid(left.id, right.id)
    && left.name === right.name
    && left.startDate === right.startDate
    && left.endDate === right.endDate
    && left.timeZone === right.timeZone
    && left.endedAt === right.endedAt
    && hasSameNullableUuid(left.previousSeasonId, right.previousSeasonId)
    && hasSameRoundSchedule(left.roundSchedule, right.roundSchedule)
}

function isTeamSummary(value: unknown) {
  return isRecord(value)
    && isUuid(value.id)
    && isNonEmptyString(value.name)
}

function isContinuitySignal(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'reason',
    'recommendedAction',
    'roleId',
    'severity',
    'title',
    'type',
  ])
    && isUuid(value.roleId)
    && isNullableString(value.relevantDate)
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
    'authorMemberId',
    'authorName',
    'createdAt',
    'id',
    'reason',
    'title',
  ])
    && isInstant(value.createdAt)
    && isNullableInstant(value.archivedAt)
    && hasUuidFields(value, ['authorMemberId', 'id'])
    && hasNonEmptyStringFields(value, ['authorName', 'createdAt', 'reason', 'title'])
    && isUuidArray(value.roleIds)
}

function isHandoffItem(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['category', 'id', 'label', 'roleId'])
    && typeof value.completed === 'boolean'
    && isNullableInstant(value.archivedAt)
    && isNullableInstant(value.createdAt)
    && hasUuidFields(value, ['id', 'roleId'])
    && isNonEmptyString(value.label)
    && isOneOf(value.category, ['RESPONSIBILITY', 'ROUTINE', 'RESOURCE', 'ADVICE'])
}

function isMember(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'initials', 'name', 'tone'])
    && isNullableInstant(value.deactivatedAt)
    && isUuid(value.id)
    && hasNonEmptyStringFields(value, ['initials', 'name', 'tone'])
}

function isRoleResource(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'roleId', 'title', 'url'])
    && isNullableInstant(value.createdAt)
    && isNullableString(value.description)
    && hasUuidFields(value, ['id', 'roleId'])
    && hasNonEmptyStringFields(value, ['title', 'url'])
}

function isRoleHandoff(
  value: unknown,
): value is WorkspaceProjection['roleHandoffs'][number] {
  if (!isRecord(value)) return false

  const hasValidShape = hasStringFields(value, [
    'fromMemberId',
    'id',
    'incomingAssignmentStartDate',
    'outgoingAssignmentStartDate',
    'preparedAt',
    'roleId',
    'status',
    'toMemberId',
  ])
    && isInstant(value.preparedAt)
    && isNullableInstant(value.acceptedAt)
    && isNullableInstant(value.cancelledAt)
    && isNullableInstant(value.transferredAt)
    && hasUuidFields(value, ['fromMemberId', 'id', 'roleId', 'toMemberId'])
    && hasNullableUuidFields(value, [
      'acceptedByMemberId',
      'cancelledByMemberId',
      'transferredByMemberId',
    ])
    && isOrderedCalendarPeriodWithStart(
      value.outgoingAssignmentStartDate,
      value.outgoingAssignmentEndDate,
    )
    && isOrderedCalendarPeriodWithStart(
      value.incomingAssignmentStartDate,
      value.incomingAssignmentEndDate,
    )
    && hasValidRoleHandoffCounts(
      value.activeItemCount,
      value.incompleteItemCount,
      value.resourceCount,
    )
    && isOneOf(value.status, ['PREPARING', 'TRANSFERRED', 'ACCEPTED', 'CANCELLED'])
    && typeof value.warningAcknowledged === 'boolean'

  return hasValidShape
    && !hasSameUuid(value.fromMemberId, value.toMemberId)
    && hasValidRoleHandoffState(value)
}

function isRole(value: unknown): value is Role {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'name', 'purpose'])
    && hasNullableStringFields(value, [
      'assignmentEndDate',
      'assignmentStartDate',
      'currentMemberId',
      'nextMemberId',
      'risk',
    ])
    && isUuid(value.id)
    && hasNullableUuidFields(value, ['currentMemberId', 'nextMemberId'])
    && hasNonEmptyStringFields(value, ['name', 'purpose'])
    && isOrderedCalendarPeriod(value.assignmentStartDate, value.assignmentEndDate)
    && isNonEmptyStringArray(value.responsibilities)
}

function isRoutineExecution(value: unknown): value is RoutineExecution {
  if (!isRecord(value)) return false

  const hasValidShape = hasStringFields(value, [
    'detail',
    'dueLabel',
    'id',
    'ownerRoleId',
    'phase',
    'roundId',
    'routineId',
    'status',
    'timingStatus',
    'title',
  ])
    && isNullableInstant(value.deadlineAt)
    && hasUuidFields(value, ['id', 'ownerRoleId', 'roundId', 'routineId'])
    && hasNonEmptyStringFields(value, ['detail', 'dueLabel', 'title'])
    && isOneOf(value.phase, ['BEFORE', 'DURING', 'AFTER'])
    && isOneOf(value.status, ['WAITING', 'DONE'])
    && isOneOf(value.timingStatus, [
      'UNSCHEDULED',
      'PLANNED',
      'IN_PROGRESS',
      'OVERDUE',
      'COMPLETED',
    ])

  if (!hasValidShape) return false
  if (value.status === 'DONE') return value.timingStatus === 'COMPLETED'
  if (value.timingStatus === 'COMPLETED') return false
  if (value.deadlineAt === null) return value.timingStatus === 'UNSCHEDULED'
  return value.timingStatus !== 'UNSCHEDULED'
}

function expectedRoundTimingStatus(
  executions: SeasonRound['routineExecutions'],
) {
  if (executions.length > 0
    && executions.every((execution) => execution.timingStatus === 'COMPLETED')) {
    return 'COMPLETED'
  }
  if (executions.some((execution) => execution.timingStatus === 'OVERDUE')) {
    return 'OVERDUE'
  }
  if (executions.some((execution) =>
    execution.timingStatus === 'IN_PROGRESS'
      || execution.timingStatus === 'COMPLETED')) {
    return 'IN_PROGRESS'
  }
  return executions.length === 0 ? null : 'PLANNED'
}

function isRoutineExecutionArray(
  value: unknown,
): value is SeasonRound['routineExecutions'] {
  return Array.isArray(value) && value.every(isRoutineExecution)
}

function isSeasonRound(value: unknown): value is SeasonRound {
  if (!isRecord(value)) return false

  const hasValidShape = hasStringFields(value, ['id', 'name', 'origin', 'timingStatus'])
    && isNullableInstant(value.archivedAt)
    && isNullableCalendarDate(value.meetingDate)
    && isNullableInstant(value.scheduledAt)
    && isNullableCalendarDate(value.scheduledOccurrenceDate)
    && isUuid(value.id)
    && isNonEmptyString(value.name)
    && isOneOf(value.origin, ['MANUAL', 'AUTOMATIC'])
    && isOneOf(value.timingStatus, ['PLANNED', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED'])

  if (!hasValidShape
    || !isRoutineExecutionArray(value.routineExecutions)
    || value.routineExecutions.some((execution) => !hasSameUuid(execution.roundId, value.id))) {
    return false
  }

  const hasAutomaticSchedule = value.scheduledOccurrenceDate !== null
    && value.scheduledAt !== null
    && value.meetingDate === value.scheduledOccurrenceDate
  const hasManualSchedule = value.scheduledOccurrenceDate === null
    && value.scheduledAt === null
  if (value.origin === 'AUTOMATIC' ? !hasAutomaticSchedule : !hasManualSchedule) {
    return false
  }

  const derivedTimingStatus = expectedRoundTimingStatus(value.routineExecutions)
  if (derivedTimingStatus !== null) return value.timingStatus === derivedTimingStatus
  if (value.origin === 'MANUAL') return value.timingStatus === 'PLANNED'
  return value.timingStatus === 'PLANNED' || value.timingStatus === 'IN_PROGRESS'
}

function isRoutine(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'detail',
    'dueLabel',
    'id',
    'ownerRoleId',
    'phase',
    'title',
  ])
    && isNullableInstant(value.archivedAt)
    && hasValidRoutineDeadline(value.deadlineDayOffset, value.deadlineTime)
    && hasUuidFields(value, ['id', 'ownerRoleId'])
    && hasNonEmptyStringFields(value, ['detail', 'dueLabel', 'title'])
    && isOneOf(value.phase, ['BEFORE', 'DURING', 'AFTER'])
}

function isCopiedRole(value: unknown) {
  return isRecord(value) && hasUuidFields(value, ['roleId', 'sourceRoleId'])
}

function isCopiedRoutine(value: unknown) {
  return isRecord(value) && hasUuidFields(value, ['routineId', 'sourceRoutineId'])
}

function isCreateNextSeasonResponse(value: unknown) {
  return isRecord(value)
    && isSeasonSummary(value.sourceSeason)
    && isNonEmptyString(value.sourceSeason.endedAt)
    && isSeasonSummary(value.season)
    && isUuid(value.season.previousSeasonId)
    && hasSameUuid(value.season.previousSeasonId, value.sourceSeason.id)
    && isArrayOf(value.copiedRoles, isCopiedRole)
    && isArrayOf(value.copiedRoutines, isCopiedRoutine)
}

function isRoleHandoffTransitionResponse(
  value: unknown,
  allowedStatuses: readonly RoleHandoffStatus[],
  requiresTransferHistory = false,
) {
  if (!isRecord(value)
    || !isRole(value.role)
    || !isRoleHandoff(value.handoff)
    || !allowedStatuses.includes(value.handoff.status)
    || (requiresTransferHistory && value.handoff.transferredAt === null)
    || !hasSameUuid(value.role.id, value.handoff.roleId)) {
    return false
  }

  if (value.handoff.status === 'PREPARING' || value.handoff.status === 'TRANSFERRED') {
    return hasSameUuid(value.role.currentMemberId, value.handoff.fromMemberId)
      && hasSameUuid(value.role.nextMemberId, value.handoff.toMemberId)
  }
  return true
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

export function decodeSeasonSummary(value: unknown): SeasonSummary {
  return decodeRequiredShape(value, isSeasonSummary, 'Season summary')
}

export function decodeCreateNextSeasonResponse(value: unknown): CreateNextSeasonResponse {
  return decodeRequiredShape(value, isCreateNextSeasonResponse, 'Next season creation response')
}

export function decodeMember(value: unknown): Member {
  return decodeRequiredShape(value, isMember, 'Member response')
}

export function decodeRole(value: unknown): Role {
  return decodeRequiredShape(value, isRole, 'Role response')
}

function decodeRoleHandoffTransitionResponse<T>(
  value: unknown,
  allowedStatuses: readonly RoleHandoffStatus[],
  requiresTransferHistory = false,
): T {
  return decodeRequiredShape<T>(
    value,
    (candidate) => isRoleHandoffTransitionResponse(
      candidate,
      allowedStatuses,
      requiresTransferHistory,
    ),
    'Role handoff response',
  )
}

export function decodePrepareRoleHandoffResponse(value: unknown): PrepareRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, allRoleHandoffStatuses)
}

export function decodeTransferRoleHandoffResponse(value: unknown): TransferRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, replayableTransferStatuses, true)
}

export function decodeAcceptRoleHandoffResponse(value: unknown): AcceptRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, ['ACCEPTED'])
}

export function decodeCancelRoleHandoffResponse(value: unknown): CancelRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value, ['CANCELLED'])
}

export function decodeRoutine(value: unknown): Routine {
  return decodeRequiredShape(value, isRoutine, 'Routine response')
}

export function decodeSeasonRound(value: unknown): SeasonRound {
  return decodeRequiredShape(value, isSeasonRound, 'Season round response')
}

export function decodeRoutineExecution(value: unknown): RoutineExecution {
  return decodeRequiredShape(value, isRoutineExecution, 'Routine execution response')
}

export function decodeDecision(value: unknown): Decision {
  return decodeRequiredShape(value, isDecision, 'Decision response')
}

export function decodeHandoffItem(value: unknown): HandoffItem {
  return decodeRequiredShape(value, isHandoffItem, 'Handoff item response')
}

export function decodeRoleResource(value: unknown): RoleResource {
  return decodeRequiredShape(value, isRoleResource, 'Role resource response')
}

export function decodeWorkspaceProjection(value: unknown): WorkspaceProjection {
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

export function decodeWorkspaceProjectionForScope(
  value: unknown,
  scope: { teamId: string; seasonId: string },
): WorkspaceProjection {
  const projection = decodeWorkspaceProjection(value)
  const currentSeasonListings = projection.seasons.filter(
    (season) => hasSameUuid(season.id, scope.seasonId),
  )

  if (!hasSameUuid(projection.team.id, scope.teamId)
    || !hasSameUuid(projection.season.id, scope.seasonId)
    || currentSeasonListings.length !== 1
    || !hasSameSeasonSummary(projection.season, currentSeasonListings[0]!)) {
    throw new TypeError('Workspace projection does not match its requested scope.')
  }

  return projection
}
