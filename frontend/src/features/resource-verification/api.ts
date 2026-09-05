import { getCsrfToken } from '@/features/auth/api'
import type { WorkspaceScope } from '@/features/workspace/api'
import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isSameUuid, isUuid } from '@/shared/api/responseValidation'
import type { operations } from '@/generated/api'

export type VerificationHistory = operations['getResourceVerifications']['responses'][200]['content']['application/json']
export type VerifyResourceRequest = NonNullable<operations['verifyResource']['requestBody']>['content']['application/json']

function decode(value: unknown, scope: WorkspaceScope, resourceId: string): VerificationHistory {
  if (!isJsonObject(value) || !isSameUuid(value.teamId, scope.teamId)
    || !isSameUuid(value.seasonId, scope.seasonId) || !isSameUuid(value.resourceId, resourceId)
    || !Number.isSafeInteger(value.resourceVersion) || Number(value.resourceVersion) < 0
    || !Array.isArray(value.verifications) || value.verifications.length > 20
    || !value.verifications.every((row: unknown) => isJsonObject(row)
      && isUuid(row.id) && isUuid(row.memberId) && typeof row.memberName === 'string'
      && typeof row.url === 'string' && Number.isSafeInteger(row.resourceVersion)
      && Number(row.resourceVersion) >= 0 && Number(row.resourceVersion) <= Number(value.resourceVersion)
      && (row.status === 'CONFIRMED' || row.status === 'NEEDS_UPDATE')
      && (row.note === null || row.note === undefined || typeof row.note === 'string')
      && isInstant(row.verifiedAt) && typeof row.current === 'boolean')) {
    throw new Error('자료 확인 응답을 읽지 못했습니다. 다시 불러와 주세요.')
  }
  return value as VerificationHistory
}

function path(scope: WorkspaceScope, resourceId: string) {
  return `/api/v1/teams/${encodeURIComponent(scope.teamId)}/seasons/${encodeURIComponent(scope.seasonId)}/role-resources/${encodeURIComponent(resourceId)}/verifications`
}
export function getVerificationHistory(scope: WorkspaceScope, resourceId: string) {
  return apiRequest(path(scope, resourceId), { method: 'GET',
    headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: value => decode(value, scope, resourceId) })
}
export async function verifyResource(scope: WorkspaceScope, resourceId: string, request: VerifyResourceRequest) {
  const csrf = await getCsrfToken()
  return apiRequest(path(scope, resourceId), { method: 'POST', body: request,
    headers: { 'X-Baton-Access-Key': scope.accessKey, [csrf.csrfHeaderName]: csrf.csrfToken },
    decode: value => decode(value, scope, resourceId) })
}
