import type { operations } from '@/generated/api'
import { getCsrfToken } from '@/features/auth/api'
import type { WorkspaceScope } from '@/features/workspace/api'
import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isSameUuid, isUuid } from '@/shared/api/responseValidation'

export type NotificationInbox = operations['getWorkspaceNotifications']['responses'][200]['content']['application/json']
export type Notification = NotificationInbox['notifications'][number]
export type NotificationScope = WorkspaceScope & { accountId: string }
function path(scope: NotificationScope) {
  return `/api/v1/teams/${encodeURIComponent(scope.teamId)}/seasons/${encodeURIComponent(scope.seasonId)}/notifications`
}
function decode(value: unknown, scope: NotificationScope): NotificationInbox {
  if (!isJsonObject(value) || !isSameUuid(value.teamId, scope.teamId) || !isSameUuid(value.seasonId, scope.seasonId)
    || !isSameUuid(value.accountId, scope.accountId) || !Array.isArray(value.notifications)
    || !value.notifications.every((item: unknown) => isJsonObject(item) && isUuid(item.id)
      && isUuid(item.sourceId) && isUuid(item.roleId) && typeof item.title === 'string'
      && typeof item.read === 'boolean' && isInstant(item.occurredAt)
      && ((item.kind === 'HANDOFF_REQUEST' && item.roundId === null)
        || ((item.kind === 'OVERDUE' || item.kind === 'DEADLINE_SOON') && isUuid(item.roundId))))) {
    throw new Error('내 알림 응답을 읽지 못했습니다. 다시 불러와 주세요.')
  }
  return value as NotificationInbox
}
export function getNotifications(scope: NotificationScope) {
  return apiRequest(path(scope), { method: 'GET', headers: { 'X-Baton-Access-Key': scope.accessKey },
    decode: value => decode(value, scope) })
}
export async function readNotification(scope: NotificationScope, notificationId: string) {
  const csrf = await getCsrfToken()
  return apiRequest(`${path(scope)}/${encodeURIComponent(notificationId)}/read`, { method: 'POST',
    headers: { 'X-Baton-Access-Key': scope.accessKey, [csrf.csrfHeaderName]: csrf.csrfToken },
    body: { expectedAccountId: scope.accountId }, decode: value => decode(value, scope) })
}
