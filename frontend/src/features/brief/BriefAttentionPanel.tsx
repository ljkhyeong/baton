import { useId, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import type { WorkspaceProjection } from '@/features/workspace/types'
import WorkspaceLoginLink from '@/features/workspace/WorkspaceLoginLink'
import { isActiveMember } from '@/features/workspace/workspacePresentation'
import { ApiError } from '@/shared/api/ApiError'
import { isSameUuid } from '@/shared/api/responseValidation'
import { getAttentionPage, getAttentionSummary, getAttentionTransitions, getWeeklyResolutions } from './api'
import { BriefEditionSection } from './BriefEditionSection'
import { attentionReasons } from './types'
import type { AttentionCursor, AttentionFilter, BriefScope } from './types'
import { BriefSources, BriefSourceLink } from './BriefSources'
import type { BriefSource } from './types'
import type { BriefNavigation } from './useBriefNavigation'
import './brief.scss'

type Props = { navigation: BriefNavigation; workspace: WorkspaceProjection; accessKey: string; changesDisabled: boolean; onManageMembership: () => void; onOpenSource: (source: BriefSource) => void }

export function BriefAttentionPanel(props: Props) {
  const { selection, update } = props.navigation
  return <details className="brief-attention" open={selection.open} onToggle={(event) => update({ open: event.currentTarget.open })}>
    <summary>주간 업무 점검</summary>
    {selection.open && <BriefAttentionAccess {...props} />}
  </details>
}

function BriefAttentionAccess({ workspace, accessKey, changesDisabled, onManageMembership, onOpenSource, navigation }: Props) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const membership = useCurrentAccountMembership({ accountId, teamId: workspace.team.id, accessKey })
  if (session.isPending) return <p role="status">로그인 상태를 확인하고 있습니다.</p>
  if (session.isError) return <p role="alert">로그인 상태를 확인하지 못했습니다. <button onClick={() => void session.refetch()}>다시 확인</button></p>
  if (!accountId) return <p>로그인하고 팀 구성원과 계정을 연결하면 주간 업무 점검을 볼 수 있습니다.{' '}
    <WorkspaceLoginLink teamId={workspace.team.id} seasonId={workspace.season.id} accessKey={accessKey}>로그인</WorkspaceLoginLink></p>
  if (membership.isPending) return <p role="status">팀 구성원 연결을 확인하고 있습니다.</p>
  if (membership.isError) return <p role="alert">구성원 연결을 확인하지 못했습니다. <button onClick={() => void membership.refetch()}>다시 확인</button></p>
  if (!membership.data?.claimed) return <p>점검 항목을 보려면 계정을 팀 구성원과 연결해 주세요. <button onClick={onManageMembership}>구성원 연결하기</button></p>
  const memberId = membership.data.memberId
  const member = workspace.members.find((candidate) => isSameUuid(candidate.id, memberId))
  if (!member || !isActiveMember(member)) return <p>활동 중인 팀 구성원만 주간 업무 점검을 볼 수 있습니다.</p>
  return <BriefAttentionResults key={`${accountId}:${workspace.team.id}:${workspace.season.id}:${accessKey}`}
    scope={{ accountId, teamId: workspace.team.id, seasonId: workspace.season.id, accessKey }}
    navigation={navigation} workspaceName={`${workspace.team.name} · ${workspace.season.name}`} onOpenSource={onOpenSource} timeZone={workspace.season.timeZone} readOnly={workspace.season.endedAt !== null} changesDisabled={changesDisabled} />
}

function BriefAttentionResults({ scope, timeZone, readOnly, changesDisabled, onOpenSource, navigation, workspaceName }: { navigation: BriefNavigation; workspaceName: string; scope: BriefScope; timeZone: string; readOnly: boolean; changesDisabled: boolean; onOpenSource: (source: BriefSource) => void }) {
  const historyId = useId()
  const resolutionsId = useId()
  const { filter, resolutionsOpen } = navigation.selection
  const [resolutionPage, setResolutionPage] = useState<{ after: AttentionCursor; weekStart: string; zoneId: string } | null>(null)
  const [cursor, setCursor] = useState<AttentionCursor | null>(null)
  const [selected, setSelected] = useState<AttentionCursor | null>(null)
  const [before, setBefore] = useState<number | null>(null)
  const scopeKey = ['brief', scope.accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }]
  const summary = useQuery({ queryKey: [...scopeKey, 'summary'],
    queryFn: ({ signal }) => getAttentionSummary(scope, signal), retry: false, staleTime: 0 })
  const resolutions = useQuery({ queryKey: [...scopeKey, 'weekly-resolutions', resolutionPage],
    queryFn: ({ signal }) => getWeeklyResolutions(scope, resolutionPage?.after ?? null, signal), retry: false, staleTime: 0 })
  const page = useQuery({ queryKey: [...scopeKey, 'items', filter, cursor],
    queryFn: ({ signal }) => getAttentionPage(scope, filter, cursor, signal), retry: false, staleTime: 0 })
  const history = useQuery({ queryKey: [...scopeKey, 'transitions', selected, before], enabled: selected !== null,
    queryFn: ({ signal }) => getAttentionTransitions(scope, selected!, before, signal), retry: false, staleTime: 0 })
  const changeFilter = (next: AttentionFilter) => { navigation.update({ filter: next }); setCursor(null); setSelected(null); setBefore(null) }
  const refreshResolutions = () => { setResolutionPage(null); if (resolutionPage === null) void resolutions.refetch() }
  const refresh = () => {
    setCursor(null); setSelected(null); setBefore(null); refreshResolutions()
    void summary.refetch(); if (cursor === null) void page.refetch()
  }
  const formatTime = new Intl.DateTimeFormat('ko-KR', { timeZone, dateStyle: 'short', timeStyle: 'short' })
  const summaryData = summary.isError ? undefined : summary.data
  const resolutionData = resolutions.isError ? undefined : resolutions.data
  const resolutionWeekChanged = Boolean(resolutionPage && resolutionData
    && (resolutionPage.weekStart !== resolutionData.weekStart || resolutionPage.zoneId !== resolutionData.zoneId))
  const resolutionTime = resolutionData ? new Intl.DateTimeFormat('ko-KR', {
    timeZone: resolutionData.zoneId, dateStyle: 'short', timeStyle: 'short',
  }) : null
  const pageData = page.isError ? undefined : page.data
  const accessError = [summary.error, page.error, history.error, resolutions.error].find((error) => error instanceof ApiError && (error.status === 401 || error.status === 403))
  if (accessError) return <p role="alert">{accessError.message}{' '}
    <button type="button" onClick={refresh} disabled={summary.isFetching || page.isFetching}>권한 다시 확인</button></p>
  return <div className="brief-attention-body">
    <p>BRIEF에 반영된 점검 항목입니다. 오늘 화면의 업무 위험 현황과 반영 시점이 다를 수 있습니다.</p>
    <button type="button" onClick={refresh} disabled={summary.isFetching || page.isFetching}>목록 새로고침</button>
    {summary.isPending && <p role="status">미해결 항목 요약을 불러오고 있습니다.</p>}
    {summary.isError && <p role="alert">요약을 불러오지 못했습니다. {summary.error.message}</p>}
    {summaryData && <div className="brief-summary" aria-label="미해결 항목 요약">
      <button type="button" onClick={() => changeFilter({ status: 'ACTIVE', severity: 'HIGH' })}>높은 심각도 <strong>{summaryData.highCount}건</strong></button>
      <button type="button" onClick={() => changeFilter({ status: 'ACTIVE', severity: 'MEDIUM' })}>보통 심각도 <strong>{summaryData.mediumCount}건</strong></button>
      <button type="button" onClick={() => changeFilter({ status: 'ACTIVE', revisionGap: true })}>기록 누락 이력 <strong>{summaryData.revisionGapCount}건</strong></button>
    </div>}
    <section className="brief-readiness" aria-label="이번 주 해결 요약">
      <button type="button" aria-expanded={resolutionsOpen} aria-controls={resolutionsId}
        onClick={() => navigation.update({ resolutionsOpen: !resolutionsOpen })}>
        이번 주 해결 {resolutionData ? `${resolutionData.resolvedCount}건` : resolutions.isError ? '확인 실패' : '확인 중…'}
      </button>
      {resolutionData && <small>{resolutionData.weekStart} 시작 주 ({resolutionData.zoneId}) · 확인 {new Intl.DateTimeFormat('ko-KR', {
        timeZone: resolutionData.zoneId, dateStyle: 'short', timeStyle: 'short',
      }).format(new Date(resolutionData.evaluatedAt))}</small>}
      {resolutions.isError && <p role="alert">해결 요약을 불러오지 못했습니다. <button type="button" onClick={refreshResolutions}>해결 요약 다시 조회</button></p>}
      <p className="brief-note">이번 주에 해결됐고 현재도 해결 상태인 항목입니다. 재발했거나 해결 시점을 확인할 수 없으면 제외합니다.</p>
      {resolutionsOpen && <div id={resolutionsId}>
        {resolutions.isPending && <p role="status">해결 항목을 불러오고 있습니다.</p>}
        {resolutionWeekChanged && <p role="status">조회 주간이 바뀌었습니다. 첫 페이지에서 이번 주 항목을 다시 확인해 주세요.</p>}
        {resolutionData && !resolutionWeekChanged && <BriefSources scope={scope} items={resolutionData.items} onOpen={onOpenSource}>
          {resolutionData.items.length === 0 ? <p role="status">{resolutionPage ? '이 페이지에 해결 항목이 없습니다.' : '이번 주에 해결 시점을 확인한 항목이 없습니다.'}</p>
            : <ul className="brief-items">{resolutionData.items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
              <strong>{attentionReasons[item.reasonCode]}</strong>
              <BriefSourceLink item={{ ...item, status: 'RESOLVED' }} readOnly={readOnly} />
              <small>해결 {resolutionTime!.format(new Date(item.resolvedAt))} ({resolutionData.zoneId})</small>
            </li>)}</ul>}
          {resolutionData.items.length > 0 && <details key={resolutionData.items[0]?.sourceReference} className="brief-evidence" aria-label="해결 항목 연동 상세"><summary>연동 상세</summary>
            <ul className="brief-evidence-list">{resolutionData.items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
              <strong>{attentionReasons[item.reasonCode]}</strong>
              <span>원본 항목 ID <code>{item.sourceReference}</code></span>
              <span>해결 시 변경 번호 {item.resolvedRevision}</span>
            </li>)}</ul>
          </details>}
        </BriefSources>}
        <div className="brief-pagination">
          <button type="button" disabled={resolutions.isFetching} onClick={refreshResolutions}>해결 목록 새로고침</button>
          <button type="button" disabled={resolutions.isFetching || resolutionWeekChanged || !resolutionData?.nextCursor}
            onClick={() => setResolutionPage({ after: resolutionData!.nextCursor!, weekStart: resolutionData!.weekStart, zoneId: resolutionData!.zoneId })}>다음 해결 항목</button>
        </div>
        <p className="brief-note">현재 상태가 바뀌면 건수와 목록도 달라집니다. 최신 결과는 첫 페이지부터 다시 확인해 주세요.</p>
      </div>}
    </section>
    <details className="brief-evidence"><summary>요약과 전달 기록 안내</summary>
      <p className="brief-note">요약은 미해결 항목만 집계합니다. 기록 누락 이력은 변경 기록이 빠진 적이 있는 항목 수이며, 누락 건수가 아닙니다.
        심각도별 개수와 중복되며, 조회 시점에 따라 목록과 개수가 다를 수 있습니다. 누락 이력이 없어도 모든 변경이 전송됐다는 뜻은 아닙니다.</p>
    </details>
    <div className="brief-filters">
      <label>상태<select value={filter.status} onChange={(event) => changeFilter({ ...filter, status: event.target.value as AttentionFilter['status'] })}>
        <option value="ACTIVE">미해결</option><option value="RESOLVED">해결</option></select></label>
      <label>심각도<select value={filter.severity ?? ''} onChange={(event) => changeFilter({ ...filter, severity: event.target.value as AttentionFilter['severity'] || undefined })}>
        <option value="">전체 심각도</option><option value="HIGH">높음</option><option value="MEDIUM">보통</option></select></label>
      <label>기록 누락 이력<select value={filter.revisionGap === undefined ? '' : String(filter.revisionGap)} onChange={(event) => changeFilter({ ...filter, revisionGap: event.target.value === '' ? undefined : event.target.value === 'true' })}>
        <option value="">전체</option><option value="true">누락 이력 있음</option><option value="false">누락 이력 없음</option></select></label>
    </div>
    {page.isPending && <p role="status">점검 항목 목록을 불러오고 있습니다.</p>}
    {page.isError && <p role="alert">목록을 불러오지 못했습니다. {page.error.message}</p>}
    {pageData && <BriefSources scope={scope} items={pageData.items} onOpen={onOpenSource}>
      {pageData.items.length === 0 ? <p role="status">선택한 조건에 해당하는 점검 항목이 없습니다.</p> : <ul className="brief-items">
        {pageData.items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
          <div><strong>{attentionReasons[item.reasonCode]}</strong><span>{item.severity === 'HIGH' ? '높음' : '보통'} · {item.status === 'ACTIVE' ? '미해결' : '해결'}{item.revisionGap && ' · 누락 이력 있음'}</span></div>
          <BriefSourceLink item={item} readOnly={readOnly} />
          <small>상태 기록 {formatTime.format(new Date(item.observedAt))} ({timeZone})</small>
          <button type="button" aria-controls={historyId}
            aria-expanded={selected?.eventType === item.reasonCode && selected.sourceReference === item.sourceReference}
            onClick={() => { setSelected({ eventType: item.reasonCode, sourceReference: item.sourceReference }); setBefore(null) }}>
            변경 이력 보기
          </button>
        </li>)}
      </ul>}
      {pageData.items.length > 0 && <details key={pageData.items[0]?.sourceReference} className="brief-evidence" aria-label="점검 항목 연동 상세"><summary>연동 상세</summary>
        <ul className="brief-evidence-list">{pageData.items.map((item) => <li key={`${item.reasonCode}:${item.sourceReference}`}>
          <strong>{attentionReasons[item.reasonCode]}</strong>
          <span>원본 항목 ID <code>{item.sourceReference}</code></span>
          <span>원본 변경 번호 {item.aggregateRevision}</span>
        </li>)}</ul>
      </details>}
      <div className="brief-pagination">
        <button type="button" onClick={refresh} disabled={!cursor || page.isFetching}>첫 페이지</button>
        <button type="button" disabled={!pageData.nextCursor || page.isFetching}
          onClick={() => { setCursor(pageData.nextCursor ?? null); setSelected(null); setBefore(null) }}>다음 페이지</button>
      </div>
    </BriefSources>}
    {selected && <section id={historyId} className="brief-history" aria-label="점검 항목 변경 이력">
      <h3>{attentionReasons[selected.eventType]} — 변경 이력</h3>
      <p className="brief-note">적용된 원본 변경을 최근 순서로 표시합니다. 상태가 같아도 근거가 바뀌면 기록이 남습니다.</p>
      <details className="brief-evidence"><summary>원본 기록과 확인 기준</summary>
        <code>{selected.sourceReference}</code>
        <p className="brief-note">원본 변경 번호가 큰 순서로 표시합니다. 각 기록의 누락 여부는 해당 변경에서 새로 발견한 결과이며, 항목 전체의 누락 이력과는 다릅니다.</p>
      </details>
      {history.isPending && <p role="status">변경 이력을 불러오고 있습니다.</p>}
      {history.isError && <p role="alert">변경 이력을 불러오지 못했습니다. {history.error.message}</p>}
      {!history.isError && history.data && (history.data.transitions.length === 0
        ? <p role="status">이 항목의 변경 이력이 없습니다.</p>
        : <ol className="brief-transitions">{history.data.transitions.map((entry) => <li key={entry.eventId}>
          <strong>{entry.state === 'ACTIVE' ? '미해결' : '해결'}</strong>
          <span>{formatTime.format(new Date(entry.observedAt))} ({timeZone})</span>
          <span>원본 심각도: {entry.sourceSeverity === 'CRITICAL' ? '긴급' : entry.sourceSeverity === 'WARNING' ? '주의' : '미기록'}</span>
          <details className="brief-evidence"><summary>변경 근거 보기</summary>
            <span>원본 변경 번호 {entry.aggregateRevision}</span>
            <span>{entry.detectedRevisionGap ? '이 변경에서 기록 누락 발견' : '이 변경에서 추가 누락 발견 없음'}</span>
          </details>
        </li>)}</ol>)}
      <div className="brief-pagination">
        <button type="button" disabled={history.isFetching} onClick={() => { setBefore(null); if (before === null) void history.refetch() }}>최신 이력부터 새로고침</button>
        <button type="button" disabled={history.isFetching || history.isError || !history.data?.nextBeforeAggregateRevision}
          onClick={() => setBefore(history.data?.nextBeforeAggregateRevision ?? null)}>이전 변경 이력</button>
        <button type="button" onClick={() => setSelected(null)}>변경 이력 닫기</button>
      </div>
    </section>}
    <BriefEditionSection navigation={navigation} workspaceName={workspaceName} onGenerated={refresh} scope={scope} timeZone={timeZone} readOnly={readOnly} changesDisabled={changesDisabled} onOpenSource={onOpenSource} />
  </div>
}
