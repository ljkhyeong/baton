import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject } from '@/shared/api/responseValidation'
import { attentionReasons } from './types'
import type { AttentionCursor, AttentionFilter, AttentionItem, AttentionPage, AttentionSummary, BriefScope } from './types'

function isReason(value: unknown): value is AttentionItem['reasonCode'] {
  return typeof value === 'string' && Object.hasOwn(attentionReasons, value)
}

function decodeItem(value: unknown): AttentionItem {
  if (!isJsonObject(value) || !isReason(value.reasonCode)
    || (value.severity !== 'HIGH' && value.severity !== 'MEDIUM')
    || (value.status !== 'ACTIVE' && value.status !== 'RESOLVED')
    || typeof value.sourceReference !== 'string' || !value.sourceReference.length
    || !isInstant(value.observedAt)
    || typeof value.aggregateRevision !== 'number' || !Number.isSafeInteger(value.aggregateRevision) || value.aggregateRevision < 1
    || typeof value.ruleVersion !== 'number' || !Number.isSafeInteger(value.ruleVersion) || value.ruleVersion < 1
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
  if (typeof highCount !== 'number' || !Number.isSafeInteger(highCount) || highCount < 0
    || typeof mediumCount !== 'number' || !Number.isSafeInteger(mediumCount) || mediumCount < 0
    || typeof revisionGapCount !== 'number' || !Number.isSafeInteger(revisionGapCount) || revisionGapCount < 0) {
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
