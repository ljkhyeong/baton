import { useQueries, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { getNotificationPreferences } from '@/features/notifications/api'
import { ApiError } from '@/shared/api/ApiError'
import { getWorkspace, type WorkspaceScope } from '@/features/workspace/api'
import { workspaceKeys } from '@/features/workspace/queries'
import { isActiveMember } from '@/features/workspace/workspacePresentation'
import { personalWork } from '@/features/workspace/personalWork'
import { getMyTeams } from './api'
import { MyRecentRecordsAcrossTeams } from './MyRecentRecordsAcrossTeams'

type Teams = Awaited<ReturnType<typeof getMyTeams>>['teams']
type WorkKind = 'overdue' | 'soon' | 'routine' | 'handoff'
const denied = (error: unknown) => error instanceof ApiError && [401, 403].includes(error.status)
const taskOrder: Record<WorkKind, number> = { overdue: 0, soon: 1, routine: 2, handoff: 3 }
const queryOptions = (scope: WorkspaceScope) => ({
  queryKey: workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey, scope.accountId),
  queryFn: ({ signal }: { signal: AbortSignal }) => getWorkspace(scope, signal),
  staleTime: 0,
  refetchOnWindowFocus: (query: { state: { error: Error | null } }) => !denied(query.state.error) && 'always' as const,
  refetchOnReconnect: (query: { state: { error: Error | null } }) => !denied(query.state.error) && 'always' as const,
  retry: (count: number, error: Error) => !denied(error) && count < 1,
  refetchInterval: (query: { state: { error: Error | null } }) => denied(query.state.error) ? false : 30_000,
  refetchIntervalInBackground: false,
})

export function MyWorkAcrossTeams({ accountId, teams }: { accountId: string; teams: Teams }) {
  const [filter, setFilter] = useState('all')
  const preferences = useQuery({ queryKey: ['auth', 'notification-preferences', accountId],
    queryFn: () => getNotificationPreferences(accountId), staleTime: 0 })
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
  const available = workspaces.flatMap(workspace => {
    const team = teams.find(value => value.teamId === workspace.team.id)
    return team && workspace.members.some(member => member.id === team.memberId && isActiveMember(member))
      ? [{ workspace, memberId: team.memberId }] : []
  })
  const membershipChanged = available.length !== workspaces.length
  const pending = all.filter(query => query.isPending).length
  const failed = all.filter(query => query.isError)
  const partial = pending > 0 || failed.length > 0 || membershipChanged
  const now = Date.now()
  const dueSoonAt = preferences.data ? now + preferences.data.deadlineLeadHours * 60 * 60 * 1_000 : null
  const tasks = available.flatMap(({ workspace, memberId }) => {
    const { unfinished, awaiting } = personalWork(workspace, memberId)
    const base = `/teams/${workspace.team.id}/seasons/${workspace.season.id}`
    return [
      ...unfinished.map(({ round, execution }) => {
        const deadline = execution.deadlineAt ? Date.parse(execution.deadlineAt) : null
        const kind: WorkKind = execution.timingStatus === 'OVERDUE' ? 'overdue'
          : deadline !== null && dueSoonAt !== null && deadline > now && deadline <= dueSoonAt ? 'soon' : 'routine'
        return {
          key: `${workspace.team.id}:${workspace.season.id}:${execution.id}`, title: execution.title,
          context: `${workspace.team.name} · ${workspace.season.name} · ${round.name}`,
          kind, label: kind === 'overdue' ? '기한 지남' : kind === 'soon' ? '마감 임박' : '남은 업무',
          deadline: execution.deadlineAt, timeZone: workspace.season.timeZone,
          href: `${base}?${new URLSearchParams({ workKind: 'execution', workId: execution.id, roundId: round.id })}`,
        }
      }),
      ...awaiting.map(handoff => ({
        key: `${workspace.team.id}:${workspace.season.id}:${handoff.id}`,
        title: workspace.roles.find(role => role.id === handoff.roleId)?.name ?? '인수인계',
        context: `${workspace.team.name} · ${workspace.season.name}`, kind: 'handoff' as const, label: '수락할 인수인계',
        deadline: null, timeZone: workspace.season.timeZone,
        href: `${base}?${new URLSearchParams({ workKind: 'handoff', workId: handoff.id })}`,
      })),
    ]
  }).sort((a, b) => taskOrder[a.kind] - taskOrder[b.kind]
    || (a.deadline ?? 'z').localeCompare(b.deadline ?? 'z') || a.key.localeCompare(b.key))
  const visible = tasks.filter(task => filter === 'all' || task.kind === filter)
  return <><section className="my-work-across-teams" aria-labelledby="all-my-work-title">
    <div className="my-teams-heading"><h2 id="all-my-work-title">모든 팀의 내 할 일</h2>
      <button type="button" className="text-button" disabled={preferences.isFetching || all.some(query => query.isFetching)}
        onClick={() => { void preferences.refetch(); all.forEach(query => { void query.refetch() }) }}>업무 새로고침</button></div>
    <p>내가 맡은 업무와 수락할 인수인계를 확인하세요. 마감 임박 기준은 개인 알림 설정을 따르며 종료된 시즌은 제외합니다.</p>
    <label>업무 구분<select value={filter} onChange={event => setFilter(event.target.value)}>
      <option value="all">전체</option><option value="overdue">기한 지난 업무</option><option value="soon">마감 임박 업무</option><option value="routine">그 밖의 남은 업무</option><option value="handoff">수락할 인수인계</option>
    </select></label>
    {pending > 0 && <p role="status">{pending}개 시즌의 업무를 불러오고 있습니다.</p>}
    {(failed.length > 0 || membershipChanged) && <p role="alert">일부 팀이나 시즌의 업무가 빠져 있습니다. ‘업무 새로고침’을 눌러 다시 불러오세요.</p>}
    {preferences.isError && <p role="alert">마감 임박 기준을 불러오지 못했습니다. ‘업무 새로고침’을 눌러 다시 불러오세요.</p>}
    {visible.length === 0 ? <p>{partial ? '선택한 조건에서 확인된 업무가 없습니다.' : '선택한 조건의 남은 업무가 없습니다.'}</p>
      : <ul>{visible.map(task => <li key={task.key}>
        <Link to={task.href}><strong>{task.title}</strong><span>{task.context}</span>
          <small>{task.label}{task.deadline && ` · ${new Intl.DateTimeFormat('ko-KR', { timeZone: task.timeZone, dateStyle: 'short', timeStyle: 'short' }).format(new Date(task.deadline))} (${task.timeZone}) 마감`}</small>
        </Link>
      </li>)}</ul>}
  </section>
    <MyRecentRecordsAcrossTeams workspaces={available.map(item => item.workspace)} partial={partial} />
  </>
}
