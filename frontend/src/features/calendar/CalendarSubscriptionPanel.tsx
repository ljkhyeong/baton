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
import type { CalendarCredential, CalendarScope, CalendarStatus, CalendarStatusCheck } from './types'
import './calendar.scss'

type Props = { workspace: WorkspaceProjection; accessKey: string; changesDisabled: boolean; onManageMembership: () => void }
const labels: Record<CalendarStatus, string> = {
  NOT_CREATED: '아직 구독하지 않았습니다.', IN_PROGRESS: '구독 요청을 처리 중입니다.',
  ACTIVE: '구독 중입니다.', REISSUE_REQUIRED: '새 구독 주소가 필요합니다. 주소를 재발급하고 캘린더 앱에 다시 등록해 주세요.',
  REVOKED: '구독을 해제했습니다.', REVOCATION_PENDING: '구독 해제를 처리 중입니다. 완료될 때까지 기존 주소가 작동할 수 있습니다.',
}

export function CalendarSubscriptionPanel(props: Props) {
  const [open, setOpen] = useState(false)
  return <details className="calendar-panel" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary><h2>내 캘린더에 추가</h2><span>회차 일정과 루틴 마감</span></summary>
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
  if (!accountId) return <p>로그인하고 팀 구성원과 연결하면 내 캘린더에 일정을 추가할 수 있습니다. <WorkspaceLoginLink {...scope}>로그인</WorkspaceLoginLink></p>
  if (membership.isPending) return <p role="status">팀 구성원 연결을 확인하고 있습니다.</p>
  if (membership.isError) return <p>구성원 연결을 확인하지 못했습니다. <button type="button" className="secondary-button" onClick={() => void membership.refetch()}>다시 확인</button></p>
  const memberId = membership.data?.claimed ? membership.data.memberId : ''
  const active = workspace.members.some((member) => isSameUuid(member.id, memberId) && isActiveMember(member))
  return <>
    {!memberId && <p>구독 주소를 발급하려면 팀 구성원과 연결해 주세요. <button type="button" className="secondary-button" onClick={onManageMembership}>구성원 연결하기</button></p>}
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
  const [copied, setCopied] = useState('')
  const [confirmation, setConfirmation] = useState<'rotate' | 'revoke' | null>(null)
  const actionLock = useRef(false)
  const pollUntil = useRef<number | null>(null)
  const subscription = useQuery({ queryKey, queryFn: ({ signal }) => getCalendarSubscription(scope, signal),
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: 'always', refetchIntervalInBackground: false,
    refetchInterval: (query) => {
      if (!['IN_PROGRESS', 'REVOCATION_PENDING'].includes(query.state.data?.status ?? '')) {
        pollUntil.current = null
        return false
      }
      if (query.state.status === 'error' || actionLock.current) return false
      pollUntil.current ??= Date.now() + 90_000
      return Date.now() < pollUntil.current ? 3_000 : false
    } })
  useEffect(() => {
    if (subscription.isSuccess && subscription.data) {
      onStatusChecked?.({ ...subscription.data, checkedAt: subscription.dataUpdatedAt })
    }
  }, [subscription.isSuccess, subscription.data, subscription.dataUpdatedAt, onStatusChecked])
  const operation = useMutation({
    mutationFn: async (action: 'create' | 'rotate' | 'revoke') => {
      setCredential(null)
      setCopied('')
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
  async function copyAddress() {
    if (!visibleCredential) return
    try { await navigator.clipboard.writeText(visibleCredential.feedUrl); setCopied('구독 주소를 복사했습니다.') }
    catch { setCopied('복사하지 못했습니다. 주소를 선택해 직접 복사해 주세요.') }
  }
  return <div className="calendar-content">
    <p>BATON의 회차 일정과 마감이 있는 루틴을 읽기 전용으로 구독합니다. 일정 수정은 BATON에서 해 주세요.</p>
    {subscription.isPending && <p role="status">구독 상태를 확인하고 있습니다.</p>}
    {status && <p role="status">{labels[status]}</p>}
    {status && ['IN_PROGRESS', 'REVOCATION_PENDING'].includes(status) && <p className="calendar-polling-note">
      이 항목을 열어 둔 동안 최대 90초간 자동으로 확인합니다. 오래 걸리거나 조회에 실패하면 ‘상태 다시 확인’을 눌러 주세요.
    </p>}
    {ended && <p>종료된 시즌은 새 주소를 발급할 수 없습니다. 기존 구독의 상태 확인과 해제는 가능합니다.</p>}
    {error && <p role="alert">{error instanceof Error ? error.message : '요청 결과를 확인하지 못했습니다.'} 주소가 표시되지 않으면 상태를 확인한 뒤 필요한 작업을 선택해 주세요.</p>}
    <div className="calendar-actions">
      {!managementOnly && status && ['NOT_CREATED', 'REVOKED'].includes(status) && <button type="button" className="primary-button" disabled={!ready || !canIssue} onClick={() => request('create')}>구독 주소 발급</button>}
      {!managementOnly && status && ['ACTIVE', 'REISSUE_REQUIRED'].includes(status) && <button type="button" className="secondary-button" disabled={!ready || !canIssue} onClick={() => setConfirmation('rotate')}>새 주소 발급</button>}
      {status && ['ACTIVE', 'REISSUE_REQUIRED', 'REVOCATION_PENDING'].includes(status) && <button type="button" className="secondary-button" disabled={!ready} onClick={() => setConfirmation('revoke')}>구독 해제</button>}
      <button type="button" className="secondary-button" disabled={busy} onClick={() => { pollUntil.current = null; setCredential(null); operation.reset(); void subscription.refetch() }}>상태 다시 확인</button>
    </div>
    {confirmation && <div className="calendar-confirm" role="group" aria-label={confirmation === 'rotate' ? '새 주소 발급 확인' : '구독 해제 확인'}>
      <p>{confirmation === 'rotate' ? '새 주소를 발급하면 기존 주소는 사용할 수 없습니다. 캘린더 앱에서도 이전 구독을 지우고 새 주소를 등록해 주세요.' : '구독을 해제하면 기존 주소로 일정을 가져올 수 없습니다. 캘린더 앱에 이미 저장된 일정은 앱에서 직접 제거해 주세요.'}</p>
      <button type="button" className="secondary-button" disabled={!ready || (confirmation === 'rotate' && !canIssue)} onClick={() => request(confirmation)}>{confirmation === 'rotate' ? '기존 주소를 끄고 재발급' : '구독 해제 확인'}</button>
      <button type="button" className="secondary-button" disabled={busy} onClick={() => setConfirmation(null)}>취소</button>
    </div>}
    {visibleCredential && <div className="calendar-address">
      <label>내 구독 주소<input readOnly type="text" value={visibleCredential.feedUrl} autoComplete="off" spellCheck={false} onFocus={(event) => event.currentTarget.select()} /></label>
      <button type="button" className="primary-button" onClick={() => void copyAddress()}>구독 주소 복사</button>
      {copied && <p role="status">{copied}</p>}
      <p>이 주소를 아는 사람은 일정을 볼 수 있습니다. 다른 사람에게 공유하지 마세요. 화면을 닫으면 주소가 사라지며, 다시 필요하면 새 주소를 발급해야 합니다.</p>
    </div>}
    {!managementOnly && <details className="calendar-guide"><summary>캘린더 앱에 등록하는 방법</summary>
      <ul>
        <li>Google Calendar: PC 웹의 다른 캘린더 추가 → URL로 추가에서 주소를 붙여 넣습니다.</li>
        <li>Apple 캘린더: 구독 캘린더 추가에서 주소를 붙여 넣습니다.</li>
        <li>Outlook: 캘린더 추가 → 웹에서 구독에서 주소를 붙여 넣습니다.</li>
      </ul>
      <p>파일 가져오기는 이후 변경을 반영하지 않습니다. URL 구독을 선택해 주세요. 변경 반영 시간은 캘린더 앱마다 다르며 BATON에서 즉시 갱신을 보장하지 않습니다.</p>
    </details>}
  </div>
}
