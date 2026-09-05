import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import WorkspaceLoginLink from '@/features/workspace/WorkspaceLoginLink'
import { isActiveMember } from '@/features/workspace/workspacePresentation'
import type { WorkspaceProjection } from '@/features/workspace/types'
import { ApiClientError, ApiError } from '@/shared/api/ApiError'
import { isSameUuid } from '@/shared/api/responseValidation'
import { generateBriefEdition, getLatestBriefEdition } from './api'
import type { BriefEdition, BriefScope } from './types'
import './brief.scss'

type PanelProps = {
  workspace: WorkspaceProjection
  accessKey: string
  changesDisabled: boolean
  onManageMembership: () => void
}

const reasonLabels: Record<string, string> = {
  ROLE_UNASSIGNED: '현재 담당자가 없는 역할',
  ROLE_SUCCESSOR_MISSING: '다음 담당자가 정해지지 않은 역할',
  ROLE_PREPARATION_INCOMPLETE: '인수인계 준비가 부족한 역할',
  ROUTINE_REPEATEDLY_OVERDUE: '여러 회차에서 마감이 지난 루틴',
  HANDOFF_INCOMPLETE: '전달 또는 수락을 마치지 못한 바통',
}
const severityLabels: Record<string, string> = {
  HIGH: '높음', MEDIUM: '보통', LOW: '낮음', CRITICAL: '긴급', WARNING: '주의',
}
const statusLabels: Record<string, string> = { ACTIVE: '미해소', RESOLVED: '해소됨' }

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const messages: Record<string, string> = {
      BRIEF_DELIVERY_INCOMPLETE: '운영 기록을 요약 서비스에 전달하고 있습니다. 잠시 후 생성을 다시 시도해 주세요.',
      BRIEF_GENERATION_IN_PROGRESS: '같은 운영 기록으로 요약을 만들고 있습니다. 잠시 후 최신 요약을 다시 조회해 주세요.',
      BRIEF_CONFIGURATION_ERROR: '요약 서비스의 연결 설정을 확인해야 합니다. 운영자에게 문의해 주세요.',
      BRIEF_UNAVAILABLE: '요약 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    }
    const message = messages[error.code]
    if (message) return error.requestId ? `${message} (요청 ID: ${error.requestId})` : message
    if (error.status === 401) return '로그인이 필요합니다. 로그인 상태를 다시 확인해 주세요.'
    if (error.status === 403) return '요약에 접근할 수 없습니다. 구성원 연결과 공유 링크를 확인해 주세요.'
  }
  return error instanceof Error ? error.message : '주간 운영 요약을 확인하지 못했습니다.'
}

export function BriefEditionPanel(props: PanelProps) {
  const [open, setOpen] = useState(false)
  return (
    <details className="brief-panel" onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary><h2>주간 운영 요약</h2><span>생성 시점의 점검 항목</span></summary>
      {open && <BriefAccess {...props} />}
    </details>
  )
}

function BriefAccess({ workspace, accessKey, changesDisabled, onManageMembership }: PanelProps) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const scope = { teamId: workspace.team.id, seasonId: workspace.season.id, accessKey }
  const membership = useCurrentAccountMembership({ accountId, teamId: scope.teamId, accessKey })
  if (session.isPending) return <p role="status">로그인 상태를 확인하고 있습니다.</p>
  if (session.isError) return <p>로그인 상태를 확인하지 못했습니다. <button type="button" disabled={session.isFetching} onClick={() => void session.refetch()}>로그인 상태 다시 확인</button></p>
  if (!accountId) return <p>로그인하고 팀 구성원과 연결하면 주간 운영 요약을 볼 수 있습니다. <WorkspaceLoginLink {...scope}>로그인</WorkspaceLoginLink></p>
  if (membership.isPending) return <p role="status">팀 구성원 연결을 확인하고 있습니다.</p>
  if (membership.isError) return <p>구성원 연결을 확인하지 못했습니다. <button type="button" disabled={membership.isFetching} onClick={() => void membership.refetch()}>구성원 연결 다시 확인</button></p>
  if (!membership.data?.claimed) return <p>요약을 보려면 로그인 계정을 팀 구성원과 연결해 주세요. <button type="button" onClick={onManageMembership}>구성원 연결하기</button></p>
  const memberId = membership.data.memberId
  if (!workspace.members.some((member) => isSameUuid(member.id, memberId) && isActiveMember(member))) {
    return <p>활동 중인 팀 구성원만 주간 운영 요약을 볼 수 있습니다.</p>
  }
  return <BriefContent
    key={`${accountId}:${scope.teamId}:${scope.seasonId}:${accessKey}`}
    accountId={accountId}
    scope={scope}
    ended={Boolean(workspace.season.endedAt)}
    changesDisabled={changesDisabled}
  />
}

function BriefContent({ accountId, scope, ended, changesDisabled }: {
  accountId: string
  scope: BriefScope
  ended: boolean
  changesDisabled: boolean
}) {
  const queryClient = useQueryClient()
  const queryKey = ['brief-edition', accountId, scope.teamId, scope.seasonId, { accessKey: scope.accessKey }]
  const edition = useQuery({
    queryKey,
    queryFn: ({ signal }) => getLatestBriefEdition(scope, signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: 'always',
  })
  const generation = useMutation({
    mutationFn: () => generateBriefEdition(scope),
    retry: false,
    onSuccess: () => queryClient.invalidateQueries({ queryKey, exact: true }),
  })
  const busy = edition.isFetching || generation.isPending
  const accessDenied = [edition.error, generation.error].some((error) => (
    error instanceof ApiError && (error.status === 401 || error.status === 403)
  ))
  const visibleEdition = !accessDenied && !(edition.error instanceof ApiClientError && edition.error.kind === 'invalid-response')
    ? edition.data : null

  return <div className="brief-content">
    <div className="brief-actions">
      <button type="button" className="secondary-button" disabled={busy} onClick={() => {
        void edition.refetch().then((result) => {
          if (result.isSuccess) generation.reset()
        })
      }}>{edition.isFetching ? '요약 조회 중' : '최신 요약 다시 조회'}</button>
      <button type="button" className="primary-button" disabled={busy || !edition.isSuccess || ended || changesDisabled || accessDenied} onClick={() => generation.mutate()}>
        {generation.isPending ? '요약 생성 중' : '이번 주 요약 만들기'}
      </button>
    </div>
    {ended && <p>종료된 시즌은 기존 요약만 볼 수 있습니다.</p>}
    {edition.isPending && <p role="status">최신 주간 운영 요약을 불러오고 있습니다.</p>}
    {edition.isError && <p className="brief-error" role="alert">{errorMessage(edition.error)}{visibleEdition && ' 아래에는 마지막으로 조회한 요약을 표시합니다.'}</p>}
    {generation.isError && <p className="brief-error" role="alert">
      {errorMessage(generation.error)}
      {generation.error instanceof ApiClientError && ' 생성 결과를 확인하지 못했으므로 최신 요약을 먼저 다시 조회해 주세요.'}
    </p>}
    {generation.isSuccess && <p role="status">{generation.data.created ? '주간 운영 요약을 만들었습니다.' : '같은 운영 기록의 요약이 있어 기존 결과를 사용했습니다.'}</p>}
    {edition.isSuccess && edition.data === null && <p>아직 만든 주간 운영 요약이 없습니다.{!ended && ' 이번 주 요약 만들기로 시작하세요.'}</p>}
    {visibleEdition && <EditionSnapshot edition={visibleEdition} />}
  </div>
}

function EditionSnapshot({ edition }: { edition: BriefEdition }) {
  const formatTime = new Intl.DateTimeFormat('ko-KR', {
    timeZone: edition.zoneId, year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
  return <article className="brief-edition" aria-label="최근 생성한 주간 운영 요약">
    <header>
      <span className="section-kicker">{edition.generation}번째 요약 · 점검 항목 {edition.items.length}건</span>
      <h3><time dateTime={edition.weekStart}>{edition.weekStart}</time> 시작 주</h3>
      <p><time dateTime={edition.generatedAt}>{formatTime.format(new Date(edition.generatedAt))}</time> 생성 · {edition.zoneId}</p>
    </header>
    {edition.items.length === 0 ? <p>이 요약에 포함된 점검 항목이 없습니다. 모든 업무가 완료되었다는 뜻은 아닙니다.</p> : (
      <ul className="brief-items">
        {edition.items.map((item, index) => <li key={`${item.sourceReference}:${index}`}>
          <div className="brief-item-status">
            <span>심각도: {severityLabels[item.severity] ?? item.severity}</span>
            <span>{statusLabels[item.status] ?? `상태: ${item.status}`}</span>
          </div>
          <h4>{reasonLabels[item.reasonCode] ?? '기타 점검 항목'}</h4>
          <p><time dateTime={item.observedAt}>{formatTime.format(new Date(item.observedAt))}</time> 관찰</p>
          <details className="brief-item-details"><summary>기록 식별 정보</summary>
            <dl><dt>점검 유형</dt><dd>{item.reasonCode}</dd><dt>원본 참조</dt><dd>{item.sourceReference}</dd></dl>
          </details>
        </li>)}
      </ul>
    )}
    <p className="brief-note">생성 당시의 기록이며 현재 상태와 다를 수 있습니다. 지금 필요한 조치는 오늘 화면의 ‘확인이 필요한 업무’에서 확인하세요.</p>
  </article>
}
