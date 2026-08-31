import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isUuid, isSameUuid } from '@/shared/api/responseValidation'
import { isCalendarDate } from '@/shared/lib/calendarDate'
import { getCsrfToken } from '@/features/auth/api'
import { attentionReasons } from './types'
import type { AttentionCursor, AttentionFilter, AttentionItem, AttentionPage, AttentionSummary, AttentionTransitions,
  BriefEdition, BriefGeneration, BriefScope } from './types'

function isReason(value: unknown): value is AttentionItem['reasonCode'] {
  return typeof value === 'string' && Object.hasOwn(attentionReasons, value)
}

function decodeItem(value: unknown): AttentionItem {
  if (!isJsonObject(value) || !isReason(value.reasonCode)
    || (value.severity !== 'HIGH' && value.severity !== 'MEDIUM')
    || (value.status !== 'ACTIVE' && value.status !== 'RESOLVED')
    || typeof value.sourceReference !== 'string' || !value.sourceReference.length
    || !isInstant(value.observedAt)
    || !isPositiveInteger(value.aggregateRevision) || !isPositiveInteger(value.ruleVersion)
    || typeof value.revisionGap !== 'boolean') {
    throw new Error('관심 항목 응답을 확인할 수 없습니다.')
  }
  return { reasonCode: value.reasonCode, severity: value.severity, status: value.status,
    sourceReference: value.sourceReference, observedAt: value.observedAt,
    aggregateRevision: value.aggregateRevision, ruleVersion: value.ruleVersion, revisionGap: value.revisionGap }
}

function decodePage(value: unknown): AttentionPage {
  if (!isJsonObject(value) || !Array.isArray(value.items)) throw new Error('관심 항목 목록을 확인할 수 없습니다.')
  const cursor = value.nextCursor
  if (cursor !== null && (!isJsonObject(cursor) || !isReason(cursor.eventType)
    || typeof cursor.sourceReference !== 'string' || !cursor.sourceReference.length)) {
    throw new Error('관심 항목 다음 페이지를 확인할 수 없습니다.')
  }
  return { items: value.items.map(decodeItem), nextCursor: cursor === null ? null : {
    eventType: cursor.eventType as AttentionCursor['eventType'], sourceReference: cursor.sourceReference as string,
  } }
}

function decodeSummary(value: unknown): AttentionSummary {
  if (!isJsonObject(value)) throw new Error('관심 항목 요약을 확인할 수 없습니다.')
  const { highCount, mediumCount, revisionGapCount } = value
  if (!isNonNegativeInteger(highCount) || !isNonNegativeInteger(mediumCount) || !isNonNegativeInteger(revisionGapCount)) {
    throw new Error('관심 항목 개수를 확인할 수 없습니다.')
  }
  return { highCount, mediumCount, revisionGapCount }
}

export function getAttentionSummary(scope: BriefScope, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/attention-items/summary`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: decodeSummary,
  })
}

export function getAttentionPage(scope: BriefScope, filter: AttentionFilter, cursor: AttentionCursor | null, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/attention-items`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey },
    query: { ...filter, afterEventType: cursor?.eventType, afterSourceReference: cursor?.sourceReference },
    decode: decodePage,
  })
}

function decodeTransitions(value: unknown): AttentionTransitions {
  if (!isJsonObject(value) || !Array.isArray(value.transitions)
    || (value.nextBeforeAggregateRevision !== null && !isPositiveInteger(value.nextBeforeAggregateRevision))) {
    throw new Error('상태 변화 이력을 확인할 수 없습니다.')
  }
  return { nextBeforeAggregateRevision: value.nextBeforeAggregateRevision,
    transitions: value.transitions.map((entry) => {
      if (!isJsonObject(entry) || !isUuid(entry.eventId) || !isPositiveInteger(entry.aggregateRevision)
        || (entry.state !== 'ACTIVE' && entry.state !== 'RESOLVED') || !isInstant(entry.observedAt)
        || typeof entry.detectedRevisionGap !== 'boolean') throw new Error('상태 변화 기록을 확인할 수 없습니다.')
      return { eventId: entry.eventId, aggregateRevision: entry.aggregateRevision, state: entry.state,
        observedAt: entry.observedAt, detectedRevisionGap: entry.detectedRevisionGap }
    }) }
}

export function getAttentionTransitions(scope: BriefScope, item: AttentionCursor, before: number | null, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/attention-items/transitions`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey },
    query: { ...item, beforeAggregateRevision: before }, decode: decodeTransitions,
  })
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function decodeEdition(value: unknown, scope: BriefScope): BriefEdition {
  if (!isJsonObject(value) || !isUuid(value.editionId) || !isUuid(value.workspaceId) || !isUuid(value.seasonId)
    || !isSameUuid(value.workspaceId, scope.teamId) || !isSameUuid(value.seasonId, scope.seasonId)
    || !isPositiveInteger(value.generation) || !isPositiveInteger(value.ruleVersion) || !isNonNegativeInteger(value.sourceCursor)
    || !isCalendarDate(value.weekStart) || typeof value.zoneId !== 'string' || !value.zoneId
    || !isInstant(value.generatedAt) || !isInstant(value.windowStart) || !isInstant(value.windowEnd)
    || Date.parse(value.windowStart) >= Date.parse(value.windowEnd) || !Array.isArray(value.items)) {
    throw new Error('저장된 브리프 응답을 확인할 수 없습니다.')
  }
  new Intl.DateTimeFormat('ko-KR', { timeZone: value.zoneId })
  const items = value.items.map((item) => {
    if (!isJsonObject(item) || !isReason(item.reasonCode)
      || (item.severity !== 'HIGH' && item.severity !== 'MEDIUM')
      || (item.status !== 'ACTIVE' && item.status !== 'RESOLVED')
      || typeof item.sourceReference !== 'string' || !item.sourceReference.length
      || !isInstant(item.observedAt) || !isPositiveInteger(item.ruleVersion)
      || !((item.aggregateRevision === null && item.revisionGap === null)
        || (isPositiveInteger(item.aggregateRevision) && typeof item.revisionGap === 'boolean'))) {
      throw new Error('저장된 브리프 항목을 확인할 수 없습니다.')
    }
    return { reasonCode: item.reasonCode, severity: item.severity, status: item.status, sourceReference: item.sourceReference,
      observedAt: item.observedAt, ruleVersion: item.ruleVersion, aggregateRevision: item.aggregateRevision,
      revisionGap: item.revisionGap }
  })
  return { editionId: value.editionId, workspaceId: value.workspaceId, seasonId: value.seasonId,
    generation: value.generation, ruleVersion: value.ruleVersion, sourceCursor: value.sourceCursor,
    weekStart: value.weekStart, zoneId: value.zoneId, generatedAt: value.generatedAt,
    windowStart: value.windowStart, windowEnd: value.windowEnd, items }
}

export function getLatestEdition(scope: BriefScope, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/editions/latest`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: (value) => decodeEdition(value, scope),
  })
}

export async function generateEdition(scope: BriefScope): Promise<BriefGeneration> {
  const csrf = await getCsrfToken()
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/editions`, {
    method: 'POST', headers: { 'X-Baton-Access-Key': scope.accessKey, [csrf.csrfHeaderName]: csrf.csrfToken },
    decode: (value) => {
      if (!isJsonObject(value) || !isUuid(value.executionId) || !isUuid(value.editionId)
        || !isPositiveInteger(value.generation) || !isNonNegativeInteger(value.deliveryWatermark) || !isNonNegativeInteger(value.sourceCursor)
        || typeof value.created !== 'boolean') throw new Error('브리프 생성 결과를 확인할 수 없습니다.')
      return { executionId: value.executionId, editionId: value.editionId, generation: value.generation,
        deliveryWatermark: value.deliveryWatermark, sourceCursor: value.sourceCursor, created: value.created }
    },
  })
}
