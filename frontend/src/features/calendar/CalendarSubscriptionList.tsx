import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getCalendarSubscriptions } from './api'
import { CalendarContent } from './CalendarSubscriptionPanel'
import type { CalendarSubscriptionSummary } from './types'
import './calendar.scss'

const labels: Record<CalendarSubscriptionSummary['managementStatus'], string> = {
  CHECK_REQUIRED: '상태 확인 필요', IN_PROGRESS: '처리 중', REVOKED: '해제됨', REVOCATION_PENDING: '해제 처리 중',
}

export default function CalendarSubscriptionList({ accountId }: { accountId: string }) {
  const query = useInfiniteQuery({
    queryKey: ['calendar-subscriptions', accountId],
    queryFn: ({ pageParam, signal }) => getCalendarSubscriptions(accountId, pageParam, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextAfterSeasonId ?? undefined,
    retry: false, gcTime: 0, staleTime: 0, refetchOnWindowFocus: 'always',
  })
  const rows = query.data?.pages.flatMap(page => page.subscriptions) ?? []
  const showRows = !query.isError || query.isFetchNextPageError
  return <section className="account-security-card calendar-subscription-list" aria-labelledby="my-calendar-title">
    <header>
      <span className="section-kicker">MY CALENDARS</span>
      <h3 id="my-calendar-title">내 캘린더 구독</h3>
      <p>팀과 시즌별 구독을 확인하고 해제합니다. 팀 접근 권한이 없어도 본인 구독을 정리할 수 있습니다.</p>
    </header>
    {query.isPending && <p role="status">구독 목록을 불러오고 있습니다.</p>}
    {query.isError && <p role="alert">{query.error.message} {query.isFetchNextPageError ? '이전 목록은 유지됩니다. 더 보기를 다시 눌러 주세요.' : '목록 새로고침으로 다시 확인해 주세요.'}</p>}
    {query.isSuccess && rows.length === 0 && <p className="calendar-empty">아직 구독 기록이 없습니다. 팀의 오늘 화면에서 ‘내 캘린더에 추가’를 선택해 주세요.</p>}
    {showRows && rows.length > 0 && <ul className="calendar-subscription-rows">
      {rows.map(row => <SubscriptionRow key={row.seasonId} accountId={accountId} row={row} />)}
    </ul>}
    <div className="calendar-actions">
      {query.hasNextPage && showRows && <button type="button" className="secondary-button" disabled={query.isFetching}
        onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? '불러오는 중' : '구독 더 보기'}</button>}
      <button type="button" className="secondary-button" disabled={query.isFetching}
        onClick={() => void query.refetch()}>목록 새로고침</button>
    </div>
  </section>
}

function SubscriptionRow({ accountId, row }: { accountId: string; row: CalendarSubscriptionSummary }) {
  const [open, setOpen] = useState(false)
  return <li><details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>
      <span className="calendar-subscription-name"><strong>{row.teamName}</strong><span>{row.seasonName}</span></span>
      <span className="calendar-subscription-status">{labels[row.managementStatus]}</span>
    </summary>
    {open && <div className="calendar-subscription-management">
      <CalendarContent accountId={accountId} scope={{ accountId, teamId: row.teamId, seasonId: row.seasonId, accessKey: '' }}
        canIssue={false} ended={false} managementOnly />
      <Link className="auth-secondary-link" to={`/teams/${row.teamId}/seasons/${row.seasonId}`}>팀 화면으로 이동</Link>
      <p className="account-security-note">새 구독 주소가 필요하면 접근 가능한 팀 화면에서 발급해 주세요.</p>
    </div>}
  </details></li>
}
