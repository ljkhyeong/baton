import { getCsrfToken } from '@/features/auth/api'
import { ApiClientError } from '@/shared/api/ApiError'
import { apiRequest } from '@/shared/api/client'
import { isJsonObject, isSameUuid, isUuid } from '@/shared/api/responseValidation'
import type { CalendarCredential, CalendarScope, CalendarStatus, CalendarSubscription, CalendarSubscriptionList, CalendarSubscriptionSummary, CalendarListFilters } from './types'

const statuses: CalendarStatus[] = ['NOT_CREATED', 'IN_PROGRESS', 'ACTIVE', 'REISSUE_REQUIRED', 'REVOKED', 'REVOCATION_PENDING']
const invalid = () => new ApiClientError('invalid-response', undefined)
function path(scope: CalendarScope) {
  return `/api/v1/teams/${encodeURIComponent(scope.teamId)}/seasons/${encodeURIComponent(scope.seasonId)}/calendar-subscription`
}
function decodeStatus(value: unknown, scope: CalendarScope): CalendarSubscription {
  if (!isJsonObject(value) || !isSameUuid(value.seasonId, scope.seasonId)
    || !(value.subscriptionId == null || isUuid(value.subscriptionId))
    || typeof value.status !== 'string' || !statuses.includes(value.status as CalendarStatus)
    || (value.status !== 'NOT_CREATED' && !isUuid(value.subscriptionId))) throw invalid()
  return { subscriptionId: value.subscriptionId == null ? null : value.subscriptionId as string,
    seasonId: value.seasonId as string, status: value.status as CalendarStatus }
}
function decodeCredential(value: unknown, scope: CalendarScope): CalendarCredential {
  if (!isJsonObject(value) || !isSameUuid(value.seasonId, scope.seasonId)
    || !isUuid(value.subscriptionId) || typeof value.feedUrl !== 'string') throw invalid()
  let url: URL
  try { url = new URL(value.feedUrl) } catch { throw invalid() }
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash
    || !/\/calendars\/v1\/[A-Za-z0-9_-]{43}\.ics$/.test(url.pathname)) throw invalid()
  return { subscriptionId: value.subscriptionId, seasonId: value.seasonId as string, feedUrl: value.feedUrl }
}
export function getCalendarSubscription(scope: CalendarScope, signal: AbortSignal) {
  return apiRequest(path(scope), { method: 'GET', signal,
    headers: { 'X-Baton-Account-Id': scope.accountId }, decode: (value) => decodeStatus(value, scope) })
}
export async function issueCalendarSubscription(scope: CalendarScope, rotate: boolean) {
  const csrf = await getCsrfToken()
  return apiRequest(`${path(scope)}${rotate ? '/rotate' : ''}`, { method: 'POST',
    headers: { 'X-Baton-Access-Key': scope.accessKey, 'X-Baton-Account-Id': scope.accountId, [csrf.csrfHeaderName]: csrf.csrfToken },
    decode: (value) => decodeCredential(value, scope) })
}
export async function revokeCalendarSubscription(scope: CalendarScope) {
  const csrf = await getCsrfToken()
  return apiRequest(path(scope), { method: 'DELETE', responseType: 'no-content',
    headers: { 'X-Baton-Account-Id': scope.accountId, [csrf.csrfHeaderName]: csrf.csrfToken } })
}

const managementStatuses: CalendarSubscriptionSummary['managementStatus'][] = ['CHECK_REQUIRED', 'IN_PROGRESS', 'REVOKED', 'REVOCATION_PENDING']
export function getCalendarSubscriptions(accountId: string, afterSeasonId: string | null, filters: CalendarListFilters, signal: AbortSignal) {
  return apiRequest('/api/v1/me/calendar-subscriptions', { method: 'GET', signal,
    query: { afterSeasonId, query: filters.query || undefined, includeRevoked: filters.includeRevoked ? undefined : false },
    headers: { 'X-Baton-Account-Id': accountId }, decode: (value): CalendarSubscriptionList => {
      if (!isJsonObject(value) || !isSameUuid(value.accountId, accountId) || !Array.isArray(value.subscriptions)
        || !(value.nextAfterSeasonId === null || isUuid(value.nextAfterSeasonId))) throw invalid()
      const subscriptions = value.subscriptions.map((row): CalendarSubscriptionSummary => {
        if (!isJsonObject(row) || !isUuid(row.subscriptionId) || !isUuid(row.teamId) || !isUuid(row.seasonId)
          || typeof row.teamName !== 'string' || typeof row.seasonName !== 'string'
          || !managementStatuses.includes(row.managementStatus as CalendarSubscriptionSummary['managementStatus'])) throw invalid()
        return { subscriptionId: row.subscriptionId, teamId: row.teamId, seasonId: row.seasonId,
          teamName: row.teamName, seasonName: row.seasonName,
          managementStatus: row.managementStatus as CalendarSubscriptionSummary['managementStatus'] }
      })
      if (value.nextAfterSeasonId !== null && (!isSameUuid(value.nextAfterSeasonId, subscriptions.at(-1)?.seasonId)
        || isSameUuid(value.nextAfterSeasonId, afterSeasonId))) throw invalid()
      return { accountId, subscriptions, nextAfterSeasonId: value.nextAfterSeasonId as string | null }
    } })
}
