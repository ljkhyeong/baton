import { getCsrfToken } from '@/features/auth/api'
import type { WorkspaceScope } from '@/features/workspace/api'
import { apiRequest } from '@/shared/api/client'
import { isJsonObject, isSameUuid, isNullableInstant } from '@/shared/api/responseValidation'
import type { operations } from '@/generated/api'
import { scheduleHealthRead } from './healthReadQueue'
import { outcomeLabels } from './outcomeLabels'
import { monitoringReasonLabels } from './monitoringReasonLabels'

export type ResourceHealth = operations['inspectResourceHealth']['responses'][200]['content']['application/json']
export type ResourceCheck = operations['requestResourceCheck']['responses'][202]['content']['application/json']
export type ResourceHealthScope = WorkspaceScope & { resourceId: string }

const healthValues = ['UNKNOWN', 'HEALTHY', 'DEGRADED', 'BROKEN'] as const
const availabilityValues = ['AVAILABLE', 'PENDING', 'STALE', 'UNAVAILABLE', 'NOT_MONITORED'] as const
const checkValues = ['SCHEDULED', 'ALREADY_SCHEDULED', 'IN_PROGRESS'] as const

function resourcePath(scope: ResourceHealthScope) {
  return `/api/v1/teams/${encodeURIComponent(scope.teamId)}/seasons/${encodeURIComponent(scope.seasonId)}/role-resources/${encodeURIComponent(scope.resourceId)}`
}

export function decodeResourceHealth(value: unknown, resourceId: string): ResourceHealth {
  if (!isJsonObject(value) || !isSameUuid(value.resourceId, resourceId)
    || !healthValues.some((health) => health === value.health)
    || !availabilityValues.some((availability) => availability === value.availability)
    || !isNullableInstant(value.lastCheckedAt)
    || !isNullableInstant(value.lastConclusiveAt)
    || typeof value.checkRequestAllowed !== 'boolean') {
    throw new TypeError('자료 연결 상태 응답이 올바르지 않습니다.')
  }
  const lastOutcome = value.lastOutcome ?? null
  const consecutiveFailures = value.consecutiveFailures ?? null
  const monitoringReason = value.monitoringReason ?? null
  if ((lastOutcome !== null && (typeof lastOutcome !== 'string' || !Object.hasOwn(outcomeLabels, lastOutcome)))
    || (consecutiveFailures !== null && (typeof consecutiveFailures !== 'number'
      || !Number.isSafeInteger(consecutiveFailures) || consecutiveFailures < 0))) {
    throw new TypeError('자료 점검 원인과 실패 횟수가 올바르지 않습니다.')
  }
  if (monitoringReason !== null && (typeof monitoringReason !== 'string'
    || !Object.hasOwn(monitoringReasonLabels, monitoringReason))) {
    throw new TypeError('자료 자동 점검 사유가 올바르지 않습니다.')
  }
  return { ...value, lastOutcome, consecutiveFailures, monitoringReason } as ResourceHealth
}

export function getResourceHealth(scope: ResourceHealthScope, signal?: AbortSignal) {
  return scheduleHealthRead((requestSignal) => apiRequest(`${resourcePath(scope)}/health`, {
    method: 'GET', signal: requestSignal,
    headers: { 'X-Baton-Access-Key': scope.accessKey },
    decode: (value) => decodeResourceHealth(value, scope.resourceId),
  }), signal)
}

export async function requestResourceCheck(scope: ResourceHealthScope): Promise<ResourceCheck> {
  const csrf = await getCsrfToken()
  return apiRequest(`${resourcePath(scope)}/check-requests`, {
    method: 'POST',
    headers: { 'X-Baton-Access-Key': scope.accessKey, [csrf.csrfHeaderName]: csrf.csrfToken },
    decode: (value) => {
      if (!isJsonObject(value) || !isSameUuid(value.resourceId, scope.resourceId)
        || !checkValues.some((status) => status === value.status)) {
        throw new TypeError('자료 재점검 접수 응답이 올바르지 않습니다.')
      }
      return value as ResourceCheck
    },
  })
}
