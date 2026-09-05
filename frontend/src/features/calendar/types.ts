import type { operations } from '@/generated/api'

type StatusResponse = operations['getCalendarSubscription']['responses'][200]['content']['application/json']
export type CalendarCredential = operations['createCalendarSubscription']['responses'][201]['content']['application/json']
export type CalendarStatus = 'NOT_CREATED' | 'IN_PROGRESS' | 'ACTIVE' | 'REISSUE_REQUIRED' | 'REVOKED' | 'REVOCATION_PENDING'
export type CalendarSubscription = Omit<StatusResponse, 'status' | 'subscriptionId'> & {
  subscriptionId: string | null
  status: CalendarStatus
}
export type CalendarScope = { accountId: string; teamId: string; seasonId: string; accessKey: string }

export type CalendarSubscriptionList = Omit<operations['listCalendarSubscriptions']['responses'][200]['content']['application/json'], 'nextAfterSeasonId'> & {
  nextAfterSeasonId: string | null
}
export type CalendarSubscriptionSummary = CalendarSubscriptionList['subscriptions'][number]

export type CalendarListFilters = { query: string; includeRevoked: boolean }
export type CalendarStatusCheck = CalendarSubscription & { checkedAt: number }
