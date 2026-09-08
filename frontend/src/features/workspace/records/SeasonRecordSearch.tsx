import { useQueries } from '@tanstack/react-query'
import { getWorkspace, isWorkspaceAccessDenied, type WorkspaceScope } from '../api'
import { workspaceKeys } from '../queries'
import type { WorkspaceProjection } from '../types'
import { RecordSearchView } from './RecordSearchView'
import type { RecordSearchFilters, RecordSearchResult } from './recordSearch'

export function SeasonRecordSearch({ scope, workspace, allSeasons, onAllSeasonsChange, filters, onFiltersChange, onOpenResult }: {
  scope: WorkspaceScope
  workspace: WorkspaceProjection
  allSeasons: boolean
  onAllSeasonsChange: (value: boolean) => void
  filters: RecordSearchFilters
  onFiltersChange: (value: RecordSearchFilters) => void
  onOpenResult: (result: RecordSearchResult) => void
}) {
  const seasons = allSeasons ? workspace.seasons.filter(season => season.id !== scope.seasonId) : []
  const queries = useQueries({ queries: seasons.map(season => ({
    queryKey: workspaceKeys.detail(scope.teamId, season.id, scope.accessKey, scope.accountId),
    queryFn: ({ signal }: { signal: AbortSignal }) => getWorkspace({ ...scope, seasonId: season.id }, signal),
    staleTime: 0,
    retry: (count: number, error: Error) => !isWorkspaceAccessDenied(error) && count < 1,
    refetchInterval: (query: { state: { error: Error | null } }) => isWorkspaceAccessDenied(query.state.error) ? false : 30_000,
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: (query: { state: { error: Error | null } }) => !isWorkspaceAccessDenied(query.state.error) && 'always' as const,
    refetchOnReconnect: (query: { state: { error: Error | null } }) => !isWorkspaceAccessDenied(query.state.error) && 'always' as const,
  })) })
  const loading = queries.filter(query => query.isPending).length
  const failed = queries.flatMap((query, index) => query.isError ? [{ name: seasons[index]!.name, query }] : [])
  const available = queries.flatMap(query => query.data && !query.isError ? [query.data] : [])
  return <RecordSearchView season={workspace.season} roles={workspace.roles} decisions={workspace.decisions}
    handoffItems={workspace.handoffItems} resources={workspace.resources} otherSeasons={available}
    filters={filters} onFiltersChange={onFiltersChange} onOpenResult={onOpenResult}
    partial={loading > 0 || failed.length > 0}
    onOpenOtherSeason={(result, seasonId) => {
      const query = new URLSearchParams({ recordKind: result.kind, recordId: result.id })
      const fragment = scope.accessKey ? `#accessKey=${encodeURIComponent(scope.accessKey)}` : ''
      return `/teams/${scope.teamId}/seasons/${seasonId}?${query}${fragment}`
    }}
    scopeControl={<div className="record-search-scope">
      <label>검색할 시즌<select value={allSeasons ? 'all' : 'current'} onChange={event => {
        onAllSeasonsChange(event.target.value === 'all'); onFiltersChange({ ...filters, roleId: '' })
      }}><option value="current">현재 시즌 · {workspace.season.name}</option><option value="all">이 팀의 모든 시즌</option></select></label>
      {loading > 0 && <p role="status">{loading}개 시즌을 불러오는 중입니다. 현재 불러온 기록부터 표시합니다.</p>}
      {failed.length > 0 && <div role="alert"><p>{failed.map(item => item.name).join(', ')} 시즌을 불러오지 못했습니다. 검색 결과에서 제외했습니다.</p>
        <button type="button" className="secondary-button" onClick={() => { failed.forEach(item => { void item.query.refetch() }) }}>누락된 시즌 다시 불러오기</button></div>}
    </div>}
  />
}
