import { useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { getCalendarSubscriptions } from './api'
import { CalendarContent } from './CalendarSubscriptionPanel'
import type { CalendarSubscriptionSummary, CalendarListFilters, CalendarStatus, CalendarStatusCheck } from './types'
import './calendar.scss'

const labels: Record<CalendarSubscriptionSummary['managementStatus'] | CalendarStatus, string> = {
  CHECK_REQUIRED: '상태 확인 필요', IN_PROGRESS: '처리 중', REVOKED: '해제됨', REVOCATION_PENDING: '해제 처리 중',
  NOT_CREATED: '미생성', ACTIVE: '구독 중', REISSUE_REQUIRED: '새 주소 필요',
}

export default function CalendarSubscriptionList({ accountId }: { accountId: string }) {
  const [search, setSearch] = useState('')
  const [filters, setFilters] = useState<CalendarListFilters>({ query: '', includeRevoked: true })
  const query = useInfiniteQuery({
    queryKey: ['calendar-subscriptions', accountId, filters],
    queryFn: ({ pageParam, signal }) => getCalendarSubscriptions(accountId, pageParam, filters, signal),
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
    <form className="calendar-search" role="search" aria-label="내 캘린더 구독 검색" onSubmit={event => {
      event.preventDefault()
      setFilters(current => ({ ...current, query: search.trim() }))
    }}>
      <label htmlFor="calendar-search-input">팀·시즌 검색</label>
      <div className="calendar-search-inputs">
        <input id="calendar-search-input" type="search" maxLength={100} value={search} onChange={event => setSearch(event.target.value)}
          placeholder="팀 또는 시즌 이름" />
        <button type="submit" className="secondary-button">검색</button>
      </div>
      <label className="calendar-filter"><input type="checkbox" checked={!filters.includeRevoked}
        onChange={event => setFilters(current => ({ ...current, includeRevoked: !event.target.checked }))} />해제된 구독 숨기기</label>
      {(filters.query || !filters.includeRevoked) && <button type="button" className="secondary-button" onClick={() => {
        setSearch(''); setFilters({ query: '', includeRevoked: true })
      }}>검색·필터 초기화</button>}
    </form>
    {query.isPending && <p role="status">구독 목록을 불러오고 있습니다.</p>}
    {query.isError && <p role="alert">{query.error.message} {query.isFetchNextPageError ? '이전 목록은 유지됩니다. 더 보기를 다시 눌러 주세요.' : '목록 새로고침으로 다시 확인해 주세요.'}</p>}
    {query.isSuccess && rows.length === 0 && <p className="calendar-empty">{filters.query || !filters.includeRevoked
      ? '조건에 맞는 구독이 없습니다. 검색어나 필터를 바꿔 주세요.'
      : '아직 구독 기록이 없습니다. 팀의 오늘 화면에서 ‘내 캘린더에 추가’를 선택해 주세요.'}</p>}
    {showRows && rows.length > 0 && <ul className="calendar-subscription-rows">
      {rows.map(row => <SubscriptionRow key={`${row.seasonId}:${row.subscriptionId}`} accountId={accountId} row={row} listedAt={query.dataUpdatedAt} />)}
    </ul>}
    <div className="calendar-actions">
      {query.hasNextPage && showRows && <button type="button" className="secondary-button" disabled={query.isFetching}
        onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? '불러오는 중' : '구독 더 보기'}</button>}
      <button type="button" className="secondary-button" disabled={query.isFetching}
        onClick={() => void query.refetch()}>목록 새로고침</button>
    </div>
  </section>
}

const checkedTime = new Intl.DateTimeFormat('ko-KR', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
function SubscriptionRow({ accountId, row, listedAt }: { accountId: string; row: CalendarSubscriptionSummary; listedAt: number }) {
  const [open, setOpen] = useState(false)
  const [checked, setChecked] = useState<CalendarStatusCheck | null>(null)
  const latest = checked?.subscriptionId === row.subscriptionId
    && (row.managementStatus === 'CHECK_REQUIRED' || checked.checkedAt >= listedAt || checked.status === row.managementStatus) ? checked : null
  return <li><details onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>
      <span className="calendar-subscription-name"><strong>{row.teamName}</strong><span>{row.seasonName}</span></span>
      <span className="calendar-subscription-status">
        <span>{labels[latest?.status ?? row.managementStatus]}</span>
        {latest && <time dateTime={new Date(latest.checkedAt).toISOString()}>{checkedTime.format(latest.checkedAt)} 확인</time>}
      </span>
    </summary>
    {open && <div className="calendar-subscription-management">
      <CalendarContent accountId={accountId} scope={{ accountId, teamId: row.teamId, seasonId: row.seasonId, accessKey: '' }}
        canIssue={false} ended={false} managementOnly onStatusChecked={setChecked} />
      <Link className="auth-secondary-link" to={`/teams/${row.teamId}/seasons/${row.seasonId}`}>팀 화면으로 이동</Link>
      <p className="account-security-note">새 구독 주소가 필요하면 접근 가능한 팀 화면에서 발급해 주세요.</p>
    </div>}
  </details></li>
}
