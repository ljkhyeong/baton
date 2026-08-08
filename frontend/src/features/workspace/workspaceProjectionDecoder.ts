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
  isJsonObject as isRecord,
  isNonEmptyString,
  isUuid,
} from '@/shared/api/responseValidation'

const CALENDAR_DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/

function isNullableString(value: unknown) {
  return value === null || typeof value === 'string'
}

function isNullableUuid(value: unknown) {
  return value === null || isUuid(value)
}

function isNullableNumber(value: unknown) {
  return value === null || (typeof value === 'number' && Number.isFinite(value))
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
    && isCalendarDate(value.nextOccurrenceDate)
    && isOneOf(value.recurrence, ['WEEKLY', 'BIWEEKLY'])
    && typeof value.enabled === 'boolean'
    && typeof value.generationLeadDays === 'number'
    && Number.isFinite(value.generationLeadDays)
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
    && isNullableString(value.endedAt)
    && isNullableUuid(value.previousSeasonId)
    && (value.roundSchedule === null || isRoundSchedule(value.roundSchedule))
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
    && isNullableString(value.archivedAt)
    && hasUuidFields(value, ['authorMemberId', 'id'])
    && hasNonEmptyStringFields(value, ['authorName', 'createdAt', 'reason', 'title'])
    && isUuidArray(value.roleIds)
}

function isHandoffItem(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['category', 'id', 'label', 'roleId'])
    && typeof value.completed === 'boolean'
    && hasNullableStringFields(value, ['archivedAt', 'createdAt'])
    && hasUuidFields(value, ['id', 'roleId'])
    && isNonEmptyString(value.label)
    && isOneOf(value.category, ['RESPONSIBILITY', 'ROUTINE', 'RESOURCE', 'ADVICE'])
}

function isMember(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'initials', 'name', 'tone'])
    && isNullableString(value.deactivatedAt)
    && isUuid(value.id)
    && hasNonEmptyStringFields(value, ['initials', 'name', 'tone'])
}

function isRoleResource(value: unknown) {
  if (!isRecord(value)) return false

  return hasStringFields(value, ['id', 'roleId', 'title', 'url'])
    && hasNullableStringFields(value, ['createdAt', 'description'])
    && hasUuidFields(value, ['id', 'roleId'])
    && hasNonEmptyStringFields(value, ['title', 'url'])
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
    && hasUuidFields(value, ['fromMemberId', 'id', 'roleId', 'toMemberId'])
    && hasNullableUuidFields(value, [
      'acceptedByMemberId',
      'cancelledByMemberId',
      'transferredByMemberId',
    ])
    && isNullableNumber(value.activeItemCount)
    && isNullableNumber(value.incompleteItemCount)
    && isNullableNumber(value.resourceCount)
    && isOneOf(value.status, ['PREPARING', 'TRANSFERRED', 'ACCEPTED', 'CANCELLED'])
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
    && isUuid(value.id)
    && hasNullableUuidFields(value, ['currentMemberId', 'nextMemberId'])
    && hasNonEmptyStringFields(value, ['name', 'purpose'])
    && isNonEmptyStringArray(value.responsibilities)
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
    && isUuid(value.id)
    && isNonEmptyString(value.name)
    && isArrayOf(value.routineExecutions, isRoutineExecution)
    && isOneOf(value.origin, ['MANUAL', 'AUTOMATIC'])
    && isOneOf(value.timingStatus, ['PLANNED', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED'])
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
    && isArrayOf(value.copiedRoles, isCopiedRole)
    && isArrayOf(value.copiedRoutines, isCopiedRoutine)
}

function isRoleHandoffTransitionResponse(value: unknown) {
  return isRecord(value)
    && isRole(value.role)
    && isRoleHandoff(value.handoff)
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

function decodeRoleHandoffTransitionResponse<T>(value: unknown): T {
  return decodeRequiredShape<T>(
    value,
    isRoleHandoffTransitionResponse,
    'Role handoff response',
  )
}

export function decodePrepareRoleHandoffResponse(value: unknown): PrepareRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value)
}

export function decodeTransferRoleHandoffResponse(value: unknown): TransferRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value)
}

export function decodeAcceptRoleHandoffResponse(value: unknown): AcceptRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value)
}

export function decodeCancelRoleHandoffResponse(value: unknown): CancelRoleHandoffResponse {
  return decodeRoleHandoffTransitionResponse(value)
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
