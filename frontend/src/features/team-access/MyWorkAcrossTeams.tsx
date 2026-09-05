import { useQueries } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '@/shared/api/ApiError'
import { getWorkspace, type WorkspaceScope } from '@/features/workspace/api'
import { workspaceKeys } from '@/features/workspace/queries'
import { isActiveMember } from '@/features/workspace/workspacePresentation'
import { personalWork } from '@/features/workspace/personalWork'
import { getMyTeams } from './api'

type Teams = Awaited<ReturnType<typeof getMyTeams>>['teams']
const denied = (error: unknown) => error instanceof ApiError && [401, 403].includes(error.status)
const queryOptions = (scope: WorkspaceScope) => ({
  queryKey: workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey, scope.accountId),
  queryFn: ({ signal }: { signal: AbortSignal }) => getWorkspace(scope, signal),
  staleTime: 0, refetchOnWindowFocus: 'always' as const,
  retry: (count: number, error: Error) => !denied(error) && count < 1,
  refetchInterval: (query: { state: { error: Error | null } }) => denied(query.state.error) ? false : 30_000,
  refetchIntervalInBackground: false,
})

export function MyWorkAcrossTeams({ accountId, teams }: { accountId: string; teams: Teams }) {
  const [filter, setFilter] = useState('all')
  const primary = useQueries({ queries: teams.map(team => queryOptions({ accountId, teamId: team.teamId, seasonId: team.seasonId, accessKey: '' })) })
  const otherScopes = primary.flatMap((query, index) => query.data && !query.isError
    ? query.data.seasons.filter(season => !season.endedAt && season.id !== teams[index]!.seasonId)
      .map(season => ({ accountId, teamId: teams[index]!.teamId, seasonId: season.id, accessKey: '' })) : [])
  const others = useQueries({ queries: otherScopes.map(queryOptions) })
  const blockedTeams = new Set([
    ...primary.flatMap((query, index) => query.isError ? [teams[index]!.teamId] : []),
    ...others.flatMap((query, index) => denied(query.error) ? [otherScopes[index]!.teamId] : []),
  ])
  const all = [...primary, ...others]
  const workspaces = all.flatMap(query => query.data && !query.isError && !query.data.season.endedAt
    && !blockedTeams.has(query.data.team.id) ? [query.data] : [])
  const pending = all.filter(query => query.isPending).length
  const failed = all.filter(query => query.isError)
  const tasks = workspaces.flatMap(workspace => {
    const team = teams.find(value => value.teamId === workspace.team.id)
    if (!team || !workspace.members.some(member => member.id === team.memberId && isActiveMember(member))) return []
    const { unfinished, awaiting } = personalWork(workspace, team.memberId)
    const base = `/teams/${workspace.team.id}/seasons/${workspace.season.id}`
    return [
      ...unfinished.map(({ round, execution }) => ({
        key: `${workspace.team.id}:${workspace.season.id}:${execution.id}`, title: execution.title,
        context: `${workspace.team.name} · ${workspace.season.name} · ${round.name}`,
        kind: execution.timingStatus === 'OVERDUE' ? 'overdue' : 'routine',
        label: execution.timingStatus === 'OVERDUE' ? '기한 지남' : '미완료 반복 업무',
        deadline: execution.deadlineAt, timeZone: workspace.season.timeZone,
        href: `${base}?${new URLSearchParams({ workKind: 'execution', workId: execution.id, roundId: round.id })}`,
      })),
      ...awaiting.map(handoff => ({
        key: `${workspace.team.id}:${workspace.season.id}:${handoff.id}`,
        title: workspace.roles.find(role => role.id === handoff.roleId)?.name ?? '인수인계',
        context: `${workspace.team.name} · ${workspace.season.name}`, kind: 'handoff', label: '인수인계 수락 대기',
        deadline: null, timeZone: workspace.season.timeZone,
        href: `${base}?${new URLSearchParams({ workKind: 'handoff', workId: handoff.id })}`,
      })),
    ]
  }).sort((a, b) => Number(b.kind === 'overdue') - Number(a.kind === 'overdue')
    || (a.deadline ?? 'z').localeCompare(b.deadline ?? 'z') || a.key.localeCompare(b.key))
  const visible = tasks.filter(task => filter === 'all' || task.kind === filter)
  return <section className="my-work-across-teams" aria-labelledby="all-my-work-title">
    <div className="my-teams-heading"><h2 id="all-my-work-title">모든 팀의 내 할 일</h2>
      <button type="button" className="text-button" disabled={all.some(query => query.isFetching)}
        onClick={() => { all.forEach(query => { void query.refetch() }) }}>업무 새로고침</button></div>
    <p>승인된 팀의 진행 중 시즌에서 내 담당 반복 업무와 수락 대기 인수인계를 모았습니다.</p>
    <label>볼 업무<select value={filter} onChange={event => setFilter(event.target.value)}>
      <option value="all">전체</option><option value="overdue">기한 지난 반복 업무</option><option value="handoff">수락 대기 인수인계</option>
    </select></label>
    {pending > 0 && <p role="status">{pending}개 시즌을 확인 중입니다. 먼저 불러온 업무부터 표시합니다.</p>}
    {failed.length > 0 && <p role="alert">일부 팀·시즌의 업무를 불러오지 못해 제외했습니다. 업무 새로고침으로 다시 확인해 주세요.</p>}
    {visible.length === 0 ? <p>{pending || failed.length ? '현재 확인된 업무가 없습니다.' : '선택한 조건의 남은 업무가 없습니다.'}</p>
      : <ul>{visible.map(task => <li key={task.key}>
        <Link to={task.href}><strong>{task.title}</strong><span>{task.context}</span>
          <small>{task.label}{task.deadline && ` · ${new Intl.DateTimeFormat('ko-KR', { timeZone: task.timeZone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(task.deadline))} (${task.timeZone}) 마감`}</small>
        </Link>
      </li>)}</ul>}
  </section>
}
