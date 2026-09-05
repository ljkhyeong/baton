import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isUuid, isSameUuid } from '@/shared/api/responseValidation'
import { isCalendarDate } from '@/shared/lib/calendarDate'
import { getCsrfToken } from '@/features/auth/api'
import { attentionReasons } from './types'
import type { AttentionCursor, AttentionFilter, AttentionItem, AttentionPage, AttentionSummary, AttentionTransitions,
  BriefEdition, BriefGeneration, BriefScope, BriefEditionHistory, BriefComparison, BriefSources, BriefReadiness } from './types'

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
        || typeof entry.detectedRevisionGap !== 'boolean'
        || (entry.sourceSeverity != null && entry.sourceSeverity !== 'CRITICAL' && entry.sourceSeverity !== 'WARNING')) throw new Error('상태 변화 기록을 확인할 수 없습니다.')
      return { eventId: entry.eventId, aggregateRevision: entry.aggregateRevision, state: entry.state,
        observedAt: entry.observedAt, detectedRevisionGap: entry.detectedRevisionGap, sourceSeverity: entry.sourceSeverity ?? null }
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

function decodeEditionItem(item: unknown): BriefEdition['items'][number] {
    if (!isJsonObject(item) || !isReason(item.reasonCode)
      || (item.severity !== 'HIGH' && item.severity !== 'MEDIUM')
      || (item.status !== 'ACTIVE' && item.status !== 'RESOLVED')
      || typeof item.sourceReference !== 'string' || !item.sourceReference.length
      || !isInstant(item.observedAt) || !isPositiveInteger(item.ruleVersion)
      || (item.section != null && item.section !== 'CURRENT_WEEK' && item.section !== 'CARRY_OVER')
      || !((item.aggregateRevision === null && item.revisionGap === null)
        || (isPositiveInteger(item.aggregateRevision) && typeof item.revisionGap === 'boolean'))) {
      throw new Error('저장된 브리프 항목을 확인할 수 없습니다.')
    }
    return { reasonCode: item.reasonCode, severity: item.severity, status: item.status, sourceReference: item.sourceReference,
      observedAt: item.observedAt, ruleVersion: item.ruleVersion, aggregateRevision: item.aggregateRevision,
      revisionGap: item.revisionGap, section: item.section ?? null }
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
  const items = value.items.map(decodeEditionItem)
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

function decodeEditionSummary(value: unknown): BriefEditionHistory['editions'][number] {
  if (!isJsonObject(value) || !isUuid(value.editionId) || !isPositiveInteger(value.generation)
    || !isCalendarDate(value.weekStart) || typeof value.zoneId !== 'string' || !value.zoneId
    || !isInstant(value.generatedAt) || !isNonNegativeInteger(value.sourceCursor)
    || !isPositiveInteger(value.ruleVersion) || !isNonNegativeInteger(value.itemCount)) {
    throw new Error('브리프 이력의 저장 정보를 확인할 수 없습니다.')
  }
  new Intl.DateTimeFormat('ko-KR', { timeZone: value.zoneId })
  return { editionId: value.editionId, generation: value.generation, weekStart: value.weekStart, zoneId: value.zoneId,
    generatedAt: value.generatedAt, sourceCursor: value.sourceCursor, ruleVersion: value.ruleVersion, itemCount: value.itemCount }
}

export function getEditionHistory(scope: BriefScope, before: number | null, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/editions`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey }, query: { beforeGeneration: before ?? undefined },
    decode: (value): BriefEditionHistory => {
      if (!isJsonObject(value) || !Array.isArray(value.editions)
        || (value.nextBeforeGeneration !== null && !isPositiveInteger(value.nextBeforeGeneration))) throw new Error('브리프 이력을 확인할 수 없습니다.')
      const editions = value.editions.map(decodeEditionSummary)
      if (editions.some((entry, index) => entry.generation >= (index === 0 ? before ?? Infinity : editions[index - 1]!.generation))
        || (value.nextBeforeGeneration !== null && value.nextBeforeGeneration !== editions.at(-1)?.generation)) {
        throw new Error('브리프 이력의 조회 순서를 확인할 수 없습니다.')
      }
      return { editions, nextBeforeGeneration: value.nextBeforeGeneration }
    },
  })
}

export function getEdition(scope: BriefScope, editionId: string, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/editions/${editionId}`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: (value) => {
      const edition = decodeEdition(value, scope)
      if (!isSameUuid(edition.editionId, editionId)) throw new Error('선택한 브리프와 조회 결과가 다릅니다.')
      return edition
    },
  })
}

export function compareEditions(scope: BriefScope, fromId: string, toId: string, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/editions/${toId}/changes`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey }, query: { fromEditionId: fromId },
    decode: (value): BriefComparison => {
      if (!isJsonObject(value) || !Array.isArray(value.added) || !Array.isArray(value.removed) || !Array.isArray(value.changed)) {
        throw new Error('브리프 비교 결과를 확인할 수 없습니다.')
      }
      const from = decodeEditionSummary(value.from); const to = decodeEditionSummary(value.to)
      if (!isSameUuid(from.editionId, fromId) || !isSameUuid(to.editionId, toId)) throw new Error('선택한 브리프와 비교 대상이 다릅니다.')
      return { from, to, added: value.added.map(decodeEditionItem), removed: value.removed.map(decodeEditionItem),
        changed: value.changed.map((change) => {
          if (!isJsonObject(change)) throw new Error('브리프 변경 항목을 확인할 수 없습니다.')
          const before = decodeEditionItem(change.before); const after = decodeEditionItem(change.after)
          if (before.reasonCode !== after.reasonCode || before.sourceReference !== after.sourceReference) throw new Error('변경 전후의 업무가 다릅니다.')
          return { before, after }
        }) }
    },
  })
}

export async function queryBriefSources(scope: BriefScope, sources: AttentionCursor[], signal: AbortSignal): Promise<BriefSources> {
  const csrf = await getCsrfToken()
  const result: BriefSources['sources'] = []
  for (let offset = 0; offset < sources.length; offset += 100) {
    const batch = sources.slice(offset, offset + 100)
    const response = await apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/sources/query`, {
      method: 'POST', signal, headers: { 'X-Baton-Access-Key': scope.accessKey, [csrf.csrfHeaderName]: csrf.csrfToken }, body: { sources: batch },
      decode: (value): BriefSources => {
        if (!isJsonObject(value) || !Array.isArray(value.sources) || value.sources.length !== batch.length) throw new Error('현재 업무 정보를 확인할 수 없습니다.')
        return { sources: value.sources.map((source, index) => {
          if (!isJsonObject(source) || !isReason(source.eventType) || source.eventType !== batch[index]!.eventType
            || source.sourceReference !== batch[index]!.sourceReference) throw new Error('요청한 업무와 연결 결과가 다릅니다.')
          const target = source.target
          if (target === null) return { eventType: source.eventType, sourceReference: source.sourceReference, target: null }
          if (!isJsonObject(target) || typeof target.title !== 'string' || !target.title || !isUuid(target.roleId)
            || (target.routineId !== null && !isUuid(target.routineId)) || typeof target.archived !== 'boolean'
            || (source.eventType === 'ROUTINE_REPEATEDLY_OVERDUE' ? target.routineId === null : target.routineId !== null)) {
            throw new Error('업무 이동 대상을 확인할 수 없습니다.')
          }
          return { eventType: source.eventType, sourceReference: source.sourceReference,
            target: { title: target.title, roleId: target.roleId, routineId: target.routineId, archived: target.archived } }
        }) }
      },
    })
    result.push(...response.sources)
  }
  return { sources: result }
}

export function getGenerationReadiness(scope: BriefScope, signal: AbortSignal) {
  return apiRequest(`/api/v1/teams/${scope.teamId}/seasons/${scope.seasonId}/brief/generation-readiness`, {
    method: 'GET', signal, headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: (value): BriefReadiness => {
      const statuses = ['READY', 'DELIVERY_PENDING', 'DELIVERY_FAILED', 'GENERATING', 'GENERATION_FAILED', 'SEASON_ENDED', 'DISABLED'] as const
      if (!isJsonObject(value) || !statuses.some((status) => status === value.status)
        || !isNonNegativeInteger(value.pendingCount) || !isNonNegativeInteger(value.failedCount)
        || !isInstant(value.checkedAt) || (value.lastDeliveredAt !== null && !isInstant(value.lastDeliveredAt))) throw new Error('브리프 생성 준비 상태를 확인할 수 없습니다.')
      return { status: value.status as BriefReadiness['status'], pendingCount: value.pendingCount, failedCount: value.failedCount,
        lastDeliveredAt: value.lastDeliveredAt, checkedAt: value.checkedAt }
    },
  })
}
