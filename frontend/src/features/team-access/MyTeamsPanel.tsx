import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { MyWorkAcrossTeams } from './MyWorkAcrossTeams'
import { getMyTeams, permissionNames } from './api'

export default function MyTeamsPanel({ accountId }: { accountId: string }) {
  const teams = useQuery({ queryKey: ['teams', 'mine', accountId], queryFn: () => getMyTeams(accountId),
    staleTime: 0, refetchOnWindowFocus: 'always', refetchInterval: 30_000 })
  return <section aria-label="참여 중인 팀">
    <div className="my-teams-heading"><h2>참여 중인 팀</h2>
      <button type="button" className="text-button" disabled={teams.isFetching} onClick={() => void teams.refetch()}>목록 새로고침</button>
    </div>
    {teams.isPending ? <p role="status">참여한 팀을 불러오고 있습니다.</p>
      : teams.isError ? <p role="alert">{teams.error.message} 목록을 새로고침해 주세요.</p>
        : teams.data.teams.length === 0 ? <p>이 계정으로 참여한 팀이 없습니다. 관리자가 보낸 초대 링크를 수락하세요.</p>
          : <ul className="my-teams-list">{teams.data.teams.map(team => <li key={team.teamId}>
            <div><h3>{team.teamName}</h3><p>{team.memberName} · {permissionNames[team.permission]}</p>
              <p>{team.seasonName}{team.seasonEnded && ' · 종료된 시즌'}</p></div>
            <Link className="secondary-button" to={`/teams/${team.teamId}/seasons/${team.seasonId}`}>
              {team.teamName} 열기
            </Link>
          </li>)}</ul>}
    {teams.data && !teams.isError && <MyWorkAcrossTeams accountId={accountId} teams={teams.data.teams} />}
    <p className="my-teams-note">로그인한 계정으로 이용할 수 있는 팀입니다. 공유 링크로 이용하는 팀은 시작 화면의 ‘최근 작업 공간’이나 공유 링크로 여세요.</p>
  </section>
}
