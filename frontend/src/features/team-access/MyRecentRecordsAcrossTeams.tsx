import { Link } from 'react-router-dom'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
import { compareRecordSearchResults, searchWorkspaceRecords, type RecordSearchResult } from '@/features/workspace/records/recordSearch'
import type { WorkspaceProjection } from '@/features/workspace/types'

const kindLabel = {
  decision: '결정',
  handoff: '인수인계 항목',
  resource: '역할 자료',
} satisfies Record<RecordSearchResult['kind'], string>

export function MyRecentRecordsAcrossTeams({ workspaces, partial }: {
  workspaces: WorkspaceProjection[]
  partial: boolean
}) {
  const records = workspaces.flatMap(workspace => searchWorkspaceRecords(workspace, {
    query: '', type: 'all', roleId: '', state: 'active', fromDate: '', toDate: '',
  }, workspace.season.timeZone).flatMap(record => record.createdAt ? [{
    ...record,
    createdAt: record.createdAt,
    teamId: workspace.team.id,
    teamName: workspace.team.name,
    seasonId: workspace.season.id,
    seasonName: workspace.season.name,
    timeZone: workspace.season.timeZone,
  }] : [])).sort(compareRecordSearchResults).slice(0, 5)

  return <section className="my-recent-records" aria-labelledby="my-recent-records-title">
    <h2 id="my-recent-records-title">최근 추가된 기록</h2>
    <p>여러 팀의 결정, 인수인계 항목과 역할 자료를 최근 순서로 확인하세요.</p>
    {records.length === 0 ? <p>{partial ? '현재 불러온 기록이 없습니다.' : '최근 추가된 기록이 없습니다.'}</p>
      : <ul>{records.map(record => {
        const query = new URLSearchParams({ recordKind: record.kind, recordId: record.id })
        return <li key={`${record.teamId}:${record.seasonId}:${record.key}`}>
          <Link to={`/teams/${record.teamId}/seasons/${record.seasonId}?${query}`}>
            <span>{kindLabel[record.kind]}</span><strong>{record.title}</strong>
            <small>{record.teamName} · {record.seasonName} · {formatInstant(record.createdAt, record.timeZone)} ({record.timeZone})</small>
          </Link>
        </li>
      })}</ul>}
  </section>
}
