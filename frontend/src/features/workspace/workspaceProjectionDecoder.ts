import type { WorkspaceProjection } from './types'

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isNullableString(value: unknown) {
  return value === null || typeof value === 'string'
}

function isNullableNumber(value: unknown) {
  return value === null || (typeof value === 'number' && Number.isFinite(value))
}

function isStringArray(value: unknown) {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isArrayOf(value: unknown, predicate: (item: unknown) => boolean) {
  return Array.isArray(value) && value.every(predicate)
}

function hasStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => typeof value[field] === 'string')
}

function hasNullableStringFields(
  value: Record<string, unknown>,
  fields: readonly string[],
) {
  return fields.every((field) => isNullableString(value[field]))
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

  return hasStringFields(value, [
    'firstMeetingDate',
    'meetingTime',
    'nextOccurrenceDate',
    'recurrence',
  ])
    && typeof value.enabled === 'boolean'
    && typeof value.generationLeadDays === 'number'
    && Number.isFinite(value.generationLeadDays)
}

function isSeasonSummary(value: unknown) {
  if (!isRecord(value)) return false

  return isNonEmptyString(value.id)
    && isNonEmptyString(value.name)
    && isNonEmptyString(value.startDate)
    && isNonEmptyString(value.endDate)
    && isSupportedTimeZone(value.timeZone)
    && isNullableString(value.endedAt)
    && isNullableString(value.previousSeasonId)
    && (value.roundSchedule === null || isRoundSchedule(value.roundSchedule))
}

function isTeamSummary(value: unknown) {
  return isRecord(value)
    && isNonEmptyString(value.id)
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
    && hasNullableStringFields(value, ['relevantDate', 'routineId'])
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
    && isNullableString(value.archivedAt)
    && isStringArray(value.roleIds)
}

function isHandoffItem(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['category', 'id', 'label', 'roleId'])
    && typeof value.completed === 'boolean'
    && hasNullableStringFields(value, ['archivedAt', 'createdAt'])
}

function isMember(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'initials', 'name', 'tone'])
    && isNullableString(value.deactivatedAt)
}

function isRoleResource(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'roleId', 'title', 'url'])
    && hasNullableStringFields(value, ['createdAt', 'description'])
}

function isRoleHandoff(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
    'fromMemberId',
    'id',
    'incomingAssignmentStartDate',
    'outgoingAssignmentStartDate',
    'preparedAt',
    'roleId',
    'status',
    'toMemberId',
  ])
    && hasNullableStringFields(value, [
      'acceptedAt',
      'acceptedByMemberId',
      'cancelledAt',
      'cancelledByMemberId',
      'incomingAssignmentEndDate',
      'outgoingAssignmentEndDate',
      'transferredAt',
      'transferredByMemberId',
    ])
    && isNullableNumber(value.activeItemCount)
    && isNullableNumber(value.incompleteItemCount)
    && isNullableNumber(value.resourceCount)
    && typeof value.warningAcknowledged === 'boolean'
}

function isRole(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'name', 'purpose'])
    && hasNullableStringFields(value, [
      'assignmentEndDate',
      'assignmentStartDate',
      'currentMemberId',
      'nextMemberId',
      'risk',
    ])
    && isStringArray(value.responsibilities)
}

function isRoutineExecution(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, [
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
    && isNullableString(value.deadlineAt)
}

function isSeasonRound(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'name', 'origin', 'timingStatus'])
    && hasNullableStringFields(value, [
      'archivedAt',
      'meetingDate',
      'scheduledAt',
      'scheduledOccurrenceDate',
    ])
    && isArrayOf(value.routineExecutions, isRoutineExecution)
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
    && isNullableString(value.archivedAt)
    && isNullableString(value.deadlineTime)
    && isNullableNumber(value.deadlineDayOffset)
}

export function decodeWorkspaceProjection(value: unknown): WorkspaceProjection {
  const valid = isRecord(value)
    && isTeamSummary(value.team)
    && isSeasonSummary(value.season)
    && isArrayOf(value.seasons, isSeasonSummary)
    && isArrayOf(value.continuitySignals, isContinuitySignal)
    && isArrayOf(value.decisions, isDecision)
    && isArrayOf(value.handoffItems, isHandoffItem)
    && isArrayOf(value.members, isMember)
    && isArrayOf(value.resources, isRoleResource)
    && isArrayOf(value.roleHandoffs, isRoleHandoff)
    && isArrayOf(value.roles, isRole)
    && isArrayOf(value.rounds, isSeasonRound)
    && isArrayOf(value.routines, isRoutine)

  if (!valid) {
    throw new TypeError('Workspace projection does not match its required response shape.')
  }

  return value as WorkspaceProjection
}
