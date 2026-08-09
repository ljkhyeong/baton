import { useCallback, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import WorkspaceApp from '@/features/workspace/WorkspaceApp'
import { readAccessKey, saveAccessKey } from '@/features/workspace/storage'
import { rememberRecentWorkspace } from '@/features/workspace/storage'
import type { WorkspaceProjection } from '@/features/workspace/types'

export default function WorkspacePage() {
  const { teamId = '', seasonId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const hashAccessKey = new URLSearchParams(location.hash.replace(/^#/, '')).get('accessKey') ?? ''
  const storedAccessKey = readAccessKey(teamId)
  const candidateIdentity = `${teamId}:${seasonId}:${hashAccessKey}`
  const [ignoredCandidate, setIgnoredCandidate] = useState('')
  const handledCandidates = useRef(new Set<string>())
  const candidateIsActive = Boolean(hashAccessKey && ignoredCandidate !== candidateIdentity)
  const accessKey = candidateIsActive ? hashAccessKey : storedAccessKey

  const clearFragment = useCallback(() => {
    if (location.hash) {
      window.history.replaceState(null, '', `${location.pathname}${location.search}`)
    }
  }, [location.hash, location.pathname, location.search])

  const handleWorkspaceLoaded = useCallback((workspace: WorkspaceProjection) => {
    rememberRecentWorkspace(workspace)
    if (!candidateIsActive || handledCandidates.current.has(candidateIdentity)) return

    handledCandidates.current.add(candidateIdentity)
    if (saveAccessKey(teamId, hashAccessKey)) clearFragment()
  }, [candidateIdentity, candidateIsActive, clearFragment, hashAccessKey, teamId])

  const reopenWithStoredKey = () => {
    setIgnoredCandidate(candidateIdentity)
    clearFragment()
  }

  const navigateToSeason = useCallback((nextSeasonId: string, currentAccessKey: string) => {
    const storedKey = readAccessKey(teamId)
    const fragment = storedKey === currentAccessKey
      ? ''
      : `#accessKey=${encodeURIComponent(currentAccessKey)}`
    void navigate(
      `/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(nextSeasonId)}${fragment}`,
    )
  }, [navigate, teamId])

  if (!teamId || !seasonId) {
    return (
      <main className="remote-state-page">
        <section className="remote-state" role="alert">
          <strong>작업 공간 주소를 확인해 주세요.</strong>
          <p>팀 또는 시즌 정보가 빠져 있습니다.</p>
          <Link to="/" className="primary-button">처음부터 시작하기</Link>
        </section>
      </main>
    )
  }

  if (!accessKey) {
    return (
      <main className="remote-state-page">
        <section className="remote-state" role="alert">
          <span className="section-kicker">접근 키 필요</span>
          <strong>이 작업 공간을 열 수 없어요.</strong>
          <p>팀에서 받은 공유 링크로 다시 접속해 주세요.</p>
          <Link to="/" className="secondary-button">새 작업 공간 만들기</Link>
        </section>
      </main>
    )
  }

  const storedKeyFallback = candidateIsActive && storedAccessKey && storedAccessKey !== hashAccessKey
    ? (
        <div className="workspace-key-fallback">
          <p>이 브라우저에 이전에 확인된 접근 키가 남아 있습니다.</p>
          <button type="button" className="primary-button" onClick={reopenWithStoredKey}>저장된 키로 다시 열기</button>
        </div>
      )
    : undefined

  return (
    <WorkspaceApp
      key={`${teamId}:${seasonId}:${accessKey}`}
      teamId={teamId}
      seasonId={seasonId}
      accessKey={accessKey}
      accessDeniedAction={storedKeyFallback}
      onWorkspaceLoaded={handleWorkspaceLoaded}
      onSelectSeason={navigateToSeason}
      onSeasonCreated={navigateToSeason}
    />
  )
}
