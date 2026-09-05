import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import type { WorkspaceProjection } from '@/features/workspace/types'
import WorkspaceLoginLink from '@/features/workspace/WorkspaceLoginLink'
import { isActiveMember } from '@/features/workspace/workspacePresentation'
import { ApiError } from '@/shared/api/ApiError'
import { getAttentionPage, getAttentionSummary, getAttentionTransitions } from './api'
import { BriefEditionSection } from './BriefEditionSection'
import { attentionReasons } from './types'
import type { AttentionCursor, AttentionFilter, BriefScope } from './types'
import './brief.scss'

type Props = { workspace: WorkspaceProjection; accessKey: string; onManageMembership: () => void }

export function BriefAttentionPanel(props: Props) {
  const [open, setOpen] = useState(false)
  return <details className="brief-attention" onToggle={(event) => setOpen(event.currentTarget.open)}>
    <summary>BRIEF 관심 항목</summary>
    {open && <BriefAttentionAccess {...props} />}
  </details>
}

function BriefAttentionAccess({ workspace, accessKey, onManageMembership }: Props) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const membership = useCurrentAccountMembership({ accountId, teamId: workspace.team.id, accessKey })
  if (session.isPending) return <p role="status">로그인 상태를 확인하고 있습니다.</p>
  if (session.isError) return <p role="alert">로그인 상태를 확인하지 못했습니다. <button onClick={() => void session.refetch()}>다시 확인</button></p>
  if (!accountId) return <p>로그인하고 팀 구성원과 계정을 연결하면 BRIEF 관심 항목을 볼 수 있습니다.{' '}
    <WorkspaceLoginLink teamId={workspace.team.id} seasonId={workspace.season.id} accessKey={accessKey}>로그인</WorkspaceLoginLink></p>
  if (membership.isPending) return <p role="status">팀 구성원 연결을 확인하고 있습니다.</p>
  if (membership.isError) return <p role="alert">구성원 연결을 확인하지 못했습니다. <button onClick={() => void membership.refetch()}>다시 확인</button></p>
  if (!membership.data?.claimed) return <p>관심 항목을 보려면 계정을 팀 구성원과 연결해 주세요. <button onClick={onManageMembership}>구성원 연결하기</button></p>
  const memberId = membership.data.memberId
  const member = workspace.members.find((candidate) => candidate.id === memberId)
  if (!member || !isActiveMember(member)) return <p>활동 중인 팀 구성원만 BRIEF 관심 항목을 볼 수 있습니다.</p>
  return <BriefAttentionResults key={`${accountId}:${workspace.team.id}:${workspace.season.id}:${accessKey}`}
    scope={{ accountId, teamId: workspace.team.id, seasonId: workspace.season.id, accessKey }}
    timeZone={workspace.season.timeZone} readOnly={workspace.season.endedAt !== null} />
}

function BriefAttentionResults({ scope, timeZone, readOnly }: { scope: BriefScope; timeZone: string; readOnly: boolean }) {
  const historyId = useId()
  const [filter, setFilter] = useState<AttentionFilter>({ status: 'ACTIVE' })
  const [cursor, setCursor] = useState<AttentionCursor | null>(null)
  const [selected, setSelected] = useState<AttentionCursor | null>(null)
  const [before, setBefore] = useState<number | null>(null)
  const scopeKey = ['brief', scope.accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }]
  const summary = useQuery({ queryKey: [...scopeKey, 'summary'],
    queryFn: ({ signal }) => getAttentionSummary(scope, signal), retry: false, staleTime: 0 })
  const page = useQuery({ queryKey: [...scopeKey, 'items', filter, cursor],
    queryFn: ({ signal }) => getAttentionPage(scope, filter, cursor, signal), retry: false, staleTime: 0 })
  const history = useQuery({ queryKey: [...scopeKey, 'transitions', selected, before], enabled: selected !== null,
    queryFn: ({ signal }) => getAttentionTransitions(scope, selected!, before, signal), retry: false, staleTime: 0 })
  const changeFilter = (next: AttentionFilter) => { setFilter(next); setCursor(null); setSelected(null); setBefore(null) }
  const refresh = () => { setCursor(null); setSelected(null); setBefore(null); void summary.refetch(); if (cursor === null) void page.refetch() }
  const formatTime = new Intl.DateTimeFormat('ko-KR', { timeZone, dateStyle: 'short', timeStyle: 'short' })
  const summaryData = summary.isError ? undefined : summary.data
  const pageData = page.isError ? undefined : page.data
  const accessError = [summary.error, page.error, history.error].find((error) => error instanceof ApiError && (error.status === 401 || error.status === 403))
  if (accessError) return <p role="alert">{accessError.message}{' '}
    <button type="button" onClick={refresh} disabled={summary.isFetching || page.isFetching}>권한 다시 확인</button></p>
  return <div className="brief-attention-body">
    <p>BRIEF가 마지막으로 수신한 관심 항목입니다. 오늘 화면의 레이더와 반영 시점이 다를 수 있습니다.</p>
    <button type="button" onClick={refresh} disabled={summary.isFetching || page.isFetching}>첫 페이지부터 새로고침</button>
    {summary.isPending && <p role="status">활성 항목 요약을 불러오고 있습니다.</p>}
    {summary.isError && <p role="alert">요약을 불러오지 못했습니다. {summary.error.message}</p>}
    {summaryData && <div className="brief-summary" aria-label="활성 관심 항목 요약">
      <button type="button" onClick={() => changeFilter({ status: 'ACTIVE', severity: 'HIGH' })}>높은 심각도 <strong>{summaryData.highCount}건</strong></button>
      <button type="button" onClick={() => changeFilter({ status: 'ACTIVE', severity: 'MEDIUM' })}>보통 심각도 <strong>{summaryData.mediumCount}건</strong></button>
      <button type="button" onClick={() => changeFilter({ status: 'ACTIVE', revisionGap: true })}>리비전 공백 기록 <strong>{summaryData.revisionGapCount}건</strong></button>
    </div>}
    <p className="brief-note">요약은 활성 항목만 집계합니다. 공백 기록은 심각도별 개수와 겹치며 누락 이벤트 수가 아닙니다. 요약과 목록의 조회 시점도 다릅니다.</p>
    <div className="brief-filters">
      <label>상태<select value={filter.status} onChange={(event) => changeFilter({ ...filter, status: event.target.value as AttentionFilter['status'] })}>
        <option value="ACTIVE">활성</option><option value="RESOLVED">해소</option></select></label>
      <label>심각도<select value={filter.severity ?? ''} onChange={(event) => changeFilter({ ...filter, severity: event.target.value as AttentionFilter['severity'] || undefined })}>
        <option value="">전체 심각도</option><option value="HIGH">높음</option><option value="MEDIUM">보통</option></select></label>
      <label>리비전 공백<select value={filter.revisionGap === undefined ? '' : String(filter.revisionGap)} onChange={(event) => changeFilter({ ...filter, revisionGap: event.target.value === '' ? undefined : event.target.value === 'true' })}>
        <option value="">공백 여부 전체</option><option value="true">공백 기록 있음</option><option value="false">공백 기록 없음</option></select></label>
    </div>
    <p className="brief-note">공백 기록이 없어도 원본 이벤트가 모두 전달됐다는 뜻은 아닙니다.</p>
    {page.isPending && <p role="status">관심 항목 목록을 불러오고 있습니다.</p>}
    {page.isError && <p role="alert">목록을 불러오지 못했습니다. {page.error.message}</p>}
    {pageData && <>
      {pageData.items.length === 0 ? <p role="status">선택한 조건에 해당하는 관심 항목이 없습니다.</p> : <ul className="brief-items">
        {pageData.items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
          <div><strong>{attentionReasons[item.reasonCode]}</strong><span>{item.severity === 'HIGH' ? '높음' : '보통'} · {item.status === 'ACTIVE' ? '활성' : '해소'}{item.revisionGap && ' · 공백 기록 있음'}</span></div>
          <small>원본 참조 <code>{item.sourceReference}</code></small>
          <small>관측 {formatTime.format(new Date(item.observedAt))} ({timeZone}) · 리비전 {item.aggregateRevision}</small>
          <button type="button" aria-controls={historyId}
            aria-expanded={selected?.eventType === item.reasonCode && selected.sourceReference === item.sourceReference}
            onClick={() => { setSelected({ eventType: item.reasonCode, sourceReference: item.sourceReference }); setBefore(null) }}>
            상태 변화 보기
          </button>
        </li>)}
      </ul>}
      <div className="brief-pagination">
        <button type="button" onClick={refresh} disabled={!cursor || page.isFetching}>첫 페이지</button>
        <button type="button" disabled={!pageData.nextCursor || page.isFetching}
          onClick={() => { setCursor(pageData.nextCursor ?? null); setSelected(null); setBefore(null) }}>다음 페이지</button>
      </div>
    </>}
    {selected && <section id={historyId} className="brief-history" aria-label="관심 항목 상태 변화">
      <h3>{attentionReasons[selected.eventType]} — 상태 변화</h3>
      <code>{selected.sourceReference}</code>
      <p className="brief-note">실제로 적용된 원본 리비전의 역순입니다. 같은 상태가 이어져도 근거가 바뀌면 전이가 기록됩니다.
        ‘공백 발견’은 해당 전이에서 새로 발견한 공백이며, 현재 항목의 누적 공백과 다릅니다.</p>
      {history.isPending && <p role="status">상태 변화를 불러오고 있습니다.</p>}
      {history.isError && <p role="alert">상태 변화를 불러오지 못했습니다. {history.error.message}</p>}
      {!history.isError && history.data && (history.data.transitions.length === 0
        ? <p role="status">이 항목에 적용된 상태 변화 기록이 없습니다.</p>
        : <ol className="brief-transitions">{history.data.transitions.map((entry) => <li key={entry.eventId}>
          <strong>리비전 {entry.aggregateRevision} · {entry.state === 'ACTIVE' ? '활성' : '해소'}</strong>
          <span>{formatTime.format(new Date(entry.observedAt))} ({timeZone})</span>
          <span>원본 심각도: {entry.sourceSeverity === 'CRITICAL' ? '긴급' : entry.sourceSeverity === 'WARNING' ? '주의' : '미기록'}</span>
          <span>{entry.detectedRevisionGap ? '이 전이에서 공백 발견' : '이 전이에서 새 공백 발견 없음'}</span>
        </li>)}</ol>)}
      <div className="brief-pagination">
        <button type="button" disabled={history.isFetching} onClick={() => { setBefore(null); if (before === null) void history.refetch() }}>최신 전이부터 새로고침</button>
        <button type="button" disabled={history.isFetching || history.isError || !history.data?.nextBeforeAggregateRevision}
          onClick={() => setBefore(history.data?.nextBeforeAggregateRevision ?? null)}>이전 상태 변화</button>
        <button type="button" onClick={() => setSelected(null)}>상태 변화 닫기</button>
      </div>
    </section>}
    <BriefEditionSection scope={scope} readOnly={readOnly} />
  </div>
}
