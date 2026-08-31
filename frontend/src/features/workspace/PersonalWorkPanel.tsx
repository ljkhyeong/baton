import type { ReactNode } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import { isActiveMember } from './workspacePresentation'
import type { WorkspaceProjection } from './types'
import WorkspaceLoginLink from './WorkspaceLoginLink'

export function PersonalWorkPanel({ workspace, accessKey, onManageMembership, onOpenRound, onOpenHandoff }: {
  workspace: WorkspaceProjection
  accessKey: string
  onManageMembership: () => void
  onOpenRound: (roundId: string, executionId: string) => void
  onOpenHandoff: (roleId: string) => void
}) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const membership = useCurrentAccountMembership({ accountId, teamId: workspace.team.id, accessKey })
  let content: ReactNode

  if (session.isPending) {
    content = <p>로그인 상태를 확인하고 있습니다.</p>
  } else if (session.isError) {
    content = <p>로그인 상태를 확인하지 못했습니다. <button type="button" disabled={session.isFetching} onClick={() => void session.refetch()}>다시 확인</button></p>
  } else if (!accountId) {
    content = (
      <p>
        로그인하고 팀 구성원과 계정을 연결하면 내 담당 업무를 모아 볼 수 있습니다.{' '}
        <WorkspaceLoginLink teamId={workspace.team.id} seasonId={workspace.season.id} accessKey={accessKey}>
          로그인
        </WorkspaceLoginLink>
      </p>
    )
  } else if (membership.isPending) {
    content = <p>팀 구성원 연결을 확인하고 있습니다.</p>
  } else if (membership.isError) {
    content = <p>구성원 연결을 확인하지 못했습니다. <button type="button" disabled={membership.isFetching} onClick={() => void membership.refetch()}>다시 확인</button></p>
  } else if (!membership.data?.claimed) {
    content = <p>아직 연결한 팀 구성원이 없습니다. <button type="button" onClick={onManageMembership}>구성원 연결하기</button></p>
  } else {
    const memberId = membership.data.memberId
    const member = workspace.members.find((candidate) => candidate.id === memberId)
    if (!member || !isActiveMember(member)) {
      content = <p>연결한 구성원의 활동이 종료되었거나 현재 목록에 없습니다. 팀 전체 기록은 아래에서 확인할 수 있습니다.</p>
    } else {
      const myRoleIds = new Set(workspace.roles.filter((role) => role.currentMemberId === member.id).map((role) => role.id))
      const unfinished = workspace.rounds.filter((round) => !round.archivedAt).flatMap((round) => (
        round.routineExecutions
          .filter((execution) => execution.status !== 'DONE' && myRoleIds.has(execution.ownerRoleId))
          .map((execution) => ({ round, execution }))
      )).sort((left, right) => (left.execution.deadlineAt ?? 'z').localeCompare(right.execution.deadlineAt ?? 'z'))
      const awaiting = workspace.roleHandoffs.filter((handoff) => handoff.status === 'TRANSFERRED' && handoff.toMemberId === member.id)
      const deadlineFormatter = new Intl.DateTimeFormat('ko-KR', {
        timeZone: workspace.season.timeZone, month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
      })
      content = <>
        <p>{member.name}님의 현재 담당 역할 기준입니다. 미완료 {unfinished.length}건 · 수락 대기 {awaiting.length}건</p>
        {workspace.season.endedAt && <p>종료된 시즌의 기록입니다. 수정할 수 없습니다.</p>}
        <div className="personal-work-columns">
          <div>
            <h3>미완료 루틴</h3>
            {unfinished.length === 0 ? <p>남은 담당 루틴이 없습니다.</p> : <ul>
              {unfinished.map(({ round, execution }) => <li key={execution.id}>
                <button type="button" onClick={() => onOpenRound(round.id, execution.id)}>
                  <strong>{execution.title}</strong>
                  <span>{round.name} · {workspace.roles.find((role) => role.id === execution.ownerRoleId)?.name}</span>
                  <small>{execution.timingStatus === 'OVERDUE' ? '기한 지남 · ' : ''}{execution.deadlineAt ? `${deadlineFormatter.format(new Date(execution.deadlineAt))} 마감` : '마감 미정'}</small>
                </button>
              </li>)}
            </ul>}
          </div>
          <div>
            <h3>내 수락을 기다리는 바통</h3>
            {awaiting.length === 0 ? <p>수락을 기다리는 바통이 없습니다.</p> : <ul>
              {awaiting.map((handoff) => <li key={handoff.id}>
                <button type="button" onClick={() => onOpenHandoff(handoff.roleId)}>
                  <strong>{workspace.roles.find((role) => role.id === handoff.roleId)?.name}</strong>
                  <span>전달 내용 확인하기</span>
                </button>
              </li>)}
            </ul>}
          </div>
        </div>
        <small>업무를 모아 보여주는 기능이며, 공유 링크의 접근 권한이나 담당자 확인 방식을 바꾸지 않습니다. 마감은 {workspace.season.timeZone} 기준입니다.</small>
      </>
    }
  }

  return <section className="personal-work" aria-labelledby="personal-work-title">
    <h2 id="personal-work-title">내 담당 업무</h2>
    {content}
  </section>
}
