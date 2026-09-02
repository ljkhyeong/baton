import { isInstant, isJsonObject, isNonEmptyString, isSameUuid, isUuid } from '@/shared/api/responseValidation'
import { isCalendarDate } from '@/shared/lib/calendarDate'
import type { BriefEdition, BriefEditionItem, BriefGeneration, BriefScope } from './types'

function decodeItem(value: unknown): BriefEditionItem {
  if (!isJsonObject(value)
    || !isNonEmptyString(value.sourceReference)
    || !isNonEmptyString(value.reasonCode)
    || !isNonEmptyString(value.severity)
    || !isNonEmptyString(value.status)
    || !isInstant(value.observedAt)) {
    throw new Error('주간 운영 요약 항목의 응답 형식이 올바르지 않습니다.')
  }
  return {
    sourceReference: value.sourceReference,
    reasonCode: value.reasonCode,
    severity: value.severity,
    status: value.status,
    observedAt: value.observedAt,
  }
}

export function decodeBriefEdition(value: unknown, scope: BriefScope): BriefEdition {
  if (!isJsonObject(value)
    || !isUuid(value.editionId)
    || !isSameUuid(value.workspaceId, scope.teamId)
    || !isSameUuid(value.seasonId, scope.seasonId)
    || typeof value.generation !== 'number' || !Number.isSafeInteger(value.generation) || value.generation < 1
    || !isCalendarDate(value.weekStart)
    || !isNonEmptyString(value.zoneId)
    || !isInstant(value.generatedAt)
    || !Array.isArray(value.items)) {
    throw new Error('주간 운영 요약의 응답 형식 또는 작업 공간이 올바르지 않습니다.')
  }
  new Intl.DateTimeFormat('ko-KR', { timeZone: value.zoneId }).format()
  return {
    editionId: value.editionId,
    workspaceId: value.workspaceId as string,
    seasonId: value.seasonId as string,
    generation: value.generation,
    weekStart: value.weekStart,
    zoneId: value.zoneId,
    generatedAt: value.generatedAt,
    items: value.items.map(decodeItem),
  }
}

export function decodeBriefGeneration(value: unknown): BriefGeneration {
  if (!isJsonObject(value) || !isUuid(value.editionId) || typeof value.created !== 'boolean') {
    throw new Error('주간 운영 요약 생성 응답 형식이 올바르지 않습니다.')
  }
  return { editionId: value.editionId, created: value.created }
}
