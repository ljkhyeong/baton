import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import WorkspaceLoginLink from '@/features/workspace/WorkspaceLoginLink'
import { isActiveMember } from '@/features/workspace/workspacePresentation'
import type { WorkspaceProjection } from '@/features/workspace/types'
import { ApiError } from '@/shared/api/ApiError'
import { isSameUuid } from '@/shared/api/responseValidation'
import { getCalendarSubscription, issueCalendarSubscription, revokeCalendarSubscription } from './api'
import { CalendarRegistrationGuide } from './CalendarRegistrationGuide'
import type { CalendarCredential, CalendarScope, CalendarStatus, CalendarStatusCheck } from './types'
import './calendar.scss'

type Props = { workspace: WorkspaceProjection; accessKey: string; changesDisabled: boolean; onManageMembership: () => void }
const labels: Record<CalendarStatus, string> = {
  NOT_CREATED: '아직 구독하지 않았습니다.', IN_PROGRESS: '구독 주소를 만드는 중입니다.',
  ACTIVE: '구독 중입니다.', REISSUE_REQUIRED: '새 구독 주소가 필요합니다. 주소를 재발급하고 캘린더 앱에 다시 등록해 주세요.',
  REVOKED: '구독을 해제했습니다.', REVOCATION_PENDING: '구독 해제를 처리 중입니다. 완료될 때까지 기존 주소가 작동할 수 있습니다.',
}

export function CalendarSubscriptionPanel(props: Props) {
  const [open, setOpen] = useState(false)
  return <details className="calendar-panel" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary><h2>내 캘린더에 추가</h2><span>회차 일정과 반복 업무 마감</span></summary>
    {open && <CalendarAccess {...props} />}
  </details>
}
export function CalendarSubscriptionCleanup({ teamId, seasonId }: { teamId: string; seasonId: string }) {
  const [open, setOpen] = useState(false)
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  if (!accountId) return null
  return <details className="calendar-panel" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary><h2>내 캘린더 구독 해제</h2></summary>
    <p>팀 접근 권한이 없어도 본인 구독의 상태를 확인하고 해제할 수 있습니다.</p>
    {open && <CalendarContent key={`${accountId}:${teamId}:${seasonId}`} accountId={accountId}
      scope={{ accountId, teamId, seasonId, accessKey: '' }} canIssue={false} ended={false} />}
  </details>
}
function CalendarAccess({ workspace, accessKey, changesDisabled, onManageMembership }: Props) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const scope = { accountId, teamId: workspace.team.id, seasonId: workspace.season.id, accessKey }
  const membership = useCurrentAccountMembership({ accountId, teamId: scope.teamId, accessKey })
  if (session.isPending) return <p role="status">로그인 상태를 확인하고 있습니다.</p>
  if (session.isError) return <p>로그인 상태를 확인하지 못했습니다. <button type="button" className="secondary-button" onClick={() => void session.refetch()}>다시 확인</button></p>
  if (!accountId) return <p>로그인한 뒤 팀에 등록된 본인 이름을 선택하면 내 캘린더에 일정을 추가할 수 있습니다. <WorkspaceLoginLink {...scope}>로그인</WorkspaceLoginLink></p>
  if (membership.isPending) return <p role="status">팀 구성원 연결을 확인하고 있습니다.</p>
  if (membership.isError) return <p>구성원 연결을 확인하지 못했습니다. <button type="button" className="secondary-button" onClick={() => void membership.refetch()}>다시 확인</button></p>
  const memberId = membership.data?.claimed ? membership.data.memberId : ''
  const active = workspace.members.some((member) => isSameUuid(member.id, memberId) && isActiveMember(member))
  return <>
    {!memberId && <p>먼저 ‘내 계정 연결’에서 본인 이름을 선택하세요. <button type="button" className="secondary-button" onClick={onManageMembership}>구성원 연결하기</button></p>}
    {!!memberId && !active && <p>활동 중인 구성원만 구독 주소를 발급할 수 있습니다. 기존 구독은 해제할 수 있습니다.</p>}
    <CalendarContent key={`${accountId}:${scope.teamId}:${scope.seasonId}:${accessKey}:${active}`}
      accountId={accountId} scope={scope} canIssue={active && !workspace.season.endedAt && !changesDisabled}
      ended={Boolean(workspace.season.endedAt)} />
  </>
}
export function CalendarContent({ accountId, scope, canIssue, ended, managementOnly = false, onStatusChecked }: {
  accountId: string; scope: CalendarScope; canIssue: boolean; ended: boolean; managementOnly?: boolean
  onStatusChecked?: (checked: CalendarStatusCheck) => void
}) {
  const cache = useQueryClient()
  const queryKey = ['calendar-subscription', accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }]
  const [credential, setCredential] = useState<CalendarCredential | null>(null)
  const [confirmation, setConfirmation] = useState<'rotate' | 'revoke' | null>(null)
  const actionLock = useRef(false)
  const [pollUntil, setPollUntil] = useState<number | null>(null)
  const [pollExpired, setPollExpired] = useState(false)
  const subscription = useQuery({ queryKey, queryFn: ({ signal }) => getCalendarSubscription(scope, signal),
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: 'always', refetchIntervalInBackground: false,
    refetchInterval: (query) => {
      if (!['IN_PROGRESS', 'REVOCATION_PENDING'].includes(query.state.data?.status ?? '')) return false
      if (query.state.status === 'error' || actionLock.current || pollExpired) return false
      return pollUntil === null || Date.now() < pollUntil ? 3_000 : false
    } })
  useEffect(() => {
    if (subscription.isSuccess && subscription.data) {
      onStatusChecked?.({ ...subscription.data, checkedAt: subscription.dataUpdatedAt })
    }
  }, [subscription.isSuccess, subscription.data, subscription.dataUpdatedAt, onStatusChecked])
  useEffect(() => {
    if (subscription.isSuccess && subscription.data?.status === 'REVOKED') {
      void cache.invalidateQueries({ queryKey: ['calendar-subscriptions', accountId] })
    }
  }, [cache, accountId, subscription.isSuccess, subscription.data?.status])
  const processing = ['IN_PROGRESS', 'REVOCATION_PENDING'].includes(subscription.data?.status ?? '')
  useEffect(() => {
    setPollUntil(current => processing ? current ?? Date.now() + 90_000 : null)
  }, [processing])
  useEffect(() => {
    setPollExpired(false)
    if (pollUntil === null) return
    const timeout = window.setTimeout(() => setPollExpired(true), Math.max(0, pollUntil - Date.now()))
    return () => window.clearTimeout(timeout)
  }, [pollUntil])
  const operation = useMutation({
    mutationFn: async (action: 'create' | 'rotate' | 'revoke') => {
      setCredential(null)
      setConfirmation(null)
      await cache.cancelQueries({ queryKey, exact: true })
      if (action === 'revoke') {
        await revokeCalendarSubscription(scope)
        cache.setQueryData(queryKey, { subscriptionId: subscription.data?.subscriptionId ?? null, seasonId: scope.seasonId, status: 'REVOKED' })
      } else {
        if (action === 'create' && subscription.data?.status === 'REVOKED') await revokeCalendarSubscription(scope)
        const issued = await issueCalendarSubscription(scope, action === 'rotate')
        cache.setQueryData(queryKey, { subscriptionId: issued.subscriptionId, seasonId: scope.seasonId, status: 'ACTIVE' })
        setCredential(issued)
      }
      // 구독 주소는 mutation 반환값이나 조회 캐시에 넣지 않는다.
    },
    retry: false, gcTime: 0, networkMode: 'always',
    onError: async () => { await subscription.refetch() },
    onSettled: () => {
      actionLock.current = false
      void cache.invalidateQueries({ queryKey: ['calendar-subscriptions', accountId] })
    },
  })
  const busy = operation.isPending || subscription.isFetching
  const status = subscription.data?.status
  const error = operation.variables === 'revoke' && status === 'REVOKED'
    ? subscription.error : operation.error ?? subscription.error
  const unavailable = [operation.error, subscription.error].some((value) => value instanceof ApiError && [401, 403].includes(value.status))
  const ready = !busy && !subscription.isError && !!status && !unavailable
  const request = (action: 'create' | 'rotate' | 'revoke') => {
    if (actionLock.current) return
    actionLock.current = true
    operation.mutate(action)
  }
  const visibleCredential = canIssue && !unavailable && !subscription.isError && status === 'ACTIVE' ? credential : null
  return <div className="calendar-content">
    <p>회차 일정과 반복 업무 마감을 캘린더 앱에서 확인하세요. 일정은 BATON에서만 수정할 수 있습니다.</p>
    {subscription.isPending && <p role="status">구독 상태를 확인하고 있습니다.</p>}
    {status && <p role="status">{labels[status]}</p>}
    {processing && <p className="calendar-polling-note" role="status">
      {subscription.isError ? '자동 확인을 멈췄습니다. ‘상태 다시 확인’을 누르세요.'
        : pollExpired ? '아직 완료 여부를 확인하지 못했습니다. ‘상태 다시 확인’을 누르세요.'
        : '처리가 끝났는지 확인하고 있습니다.'}
    </p>}
    {ended && <p>종료된 시즌은 새 주소를 발급할 수 없습니다. 기존 구독의 상태 확인과 해제는 가능합니다.</p>}
    {error && <p role="alert">{error instanceof Error ? error.message : '요청 결과를 확인하지 못했습니다.'}{!unavailable && <> ‘상태 다시 확인’을 눌러 주세요.</>}</p>}
    <div className="calendar-actions">
      {!managementOnly && status && ['NOT_CREATED', 'REVOKED'].includes(status) && <button type="button" className="primary-button" disabled={!ready || !canIssue} onClick={() => request('create')}>구독 주소 발급</button>}
      {!managementOnly && status && ['ACTIVE', 'REISSUE_REQUIRED'].includes(status) && <button type="button" className="secondary-button" disabled={!ready || !canIssue} onClick={() => setConfirmation('rotate')}>새 주소 발급</button>}
      {status && ['ACTIVE', 'REISSUE_REQUIRED', 'REVOCATION_PENDING'].includes(status) && <button type="button" className="secondary-button" disabled={!ready} onClick={() => setConfirmation('revoke')}>구독 해제</button>}
      <button type="button" className="secondary-button" disabled={busy} onClick={() => { setPollUntil(processing ? Date.now() + 90_000 : null); setCredential(null); operation.reset(); void subscription.refetch() }}>상태 다시 확인</button>
    </div>
    {confirmation && <div className="calendar-confirm" role="group" aria-label={confirmation === 'rotate' ? '새 주소 발급 확인' : '구독 해제 확인'}>
      <p>{confirmation === 'rotate' ? '새 주소를 발급하면 기존 주소는 사용할 수 없습니다. 캘린더 앱에서도 이전 구독을 지우고 새 주소를 등록해 주세요.' : '구독을 해제하면 기존 주소로 일정을 가져올 수 없습니다. 캘린더 앱에 이미 저장된 일정은 앱에서 직접 제거해 주세요.'}</p>
      <button type="button" className="secondary-button" disabled={!ready || (confirmation === 'rotate' && !canIssue)} onClick={() => request(confirmation)}>{confirmation === 'rotate' ? '새 주소 발급' : '구독 해제'}</button>
      <button type="button" className="secondary-button" disabled={busy} onClick={() => setConfirmation(null)}>취소</button>
    </div>}
    {!managementOnly && <CalendarRegistrationGuide key={visibleCredential?.feedUrl ?? 'guide'} feedUrl={visibleCredential?.feedUrl} />}
  </div>
}
