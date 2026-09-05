import { useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import WorkspaceApp from '@/features/workspace/WorkspaceApp'
import { workspaceKeys } from '@/features/workspace/queries'
import {
  readAccessKey,
  readWorkspaceCapabilityServerSnapshot,
  readWorkspaceCapabilitySnapshot,
  rememberRecentWorkspace,
  saveAccessKey,
  subscribeWorkspaceCapability,
} from '@/features/workspace/storage'
import type { WorkspaceProjection } from '@/features/workspace/types'

export default function WorkspacePage() {
  const { teamId = '', seasonId = '' } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const hashAccessKey = new URLSearchParams(location.hash.replace(/^#/, '')).get('accessKey') ?? ''
  const subscribeToCapability = useCallback(
    (onChange: () => void) => subscribeWorkspaceCapability(teamId, onChange),
    [teamId],
  )
  const readCapability = useCallback(
    () => readWorkspaceCapabilitySnapshot(teamId),
    [teamId],
  )
  const capability = useSyncExternalStore(
    subscribeToCapability,
    readCapability,
    readWorkspaceCapabilityServerSnapshot,
  )
  const storedAccessKey = capability.accessKey
  const candidateIdentity = `${teamId}:${seasonId}:${hashAccessKey}`
  const [ignoredCandidate, setIgnoredCandidate] = useState('')
  const [workspaceTitle, setWorkspaceTitle] = useState('작업 공간 — BATON')
  const handledCandidates = useRef(new Set<string>())
  const candidateIntroduction = useRef({
    identity: candidateIdentity,
    removalRevision: capability.removalRevision,
    wasStored: Boolean(hashAccessKey && storedAccessKey === hashAccessKey),
  })
  if (candidateIntroduction.current.identity !== candidateIdentity) {
    candidateIntroduction.current = {
      identity: candidateIdentity,
      removalRevision: capability.removalRevision,
      wasStored: Boolean(hashAccessKey && storedAccessKey === hashAccessKey),
    }
  }
  const candidateSurvivedCapabilityRemoval =
    candidateIntroduction.current.removalRevision === capability.removalRevision
    && (!candidateIntroduction.current.wasStored || storedAccessKey === hashAccessKey)
  const candidateIsActive = Boolean(
    hashAccessKey
    && ignoredCandidate !== candidateIdentity
    && candidateSurvivedCapabilityRemoval,
  )
  const accessKey = candidateIsActive ? hashAccessKey : storedAccessKey
  const previousCapability = useRef({ teamId, accessKey })

  useEffect(() => {
    const previous = previousCapability.current
    previousCapability.current = { teamId, accessKey }
    if (previous.teamId !== teamId || !previous.accessKey || accessKey) return

    const teamQueryKey = workspaceKeys.team(teamId)
    void queryClient.cancelQueries({ queryKey: teamQueryKey }).then(() => {
      queryClient.removeQueries({ queryKey: teamQueryKey })
    })
  }, [accessKey, queryClient, teamId])

  const clearFragment = useCallback(() => {
    if (location.hash) {
      window.history.replaceState(
        window.history.state,
        '',
        `${location.pathname}${location.search}`,
      )
    }
  }, [location.hash, location.pathname, location.search])

  const handleWorkspaceLoaded = useCallback((workspace: WorkspaceProjection) => {
    setWorkspaceTitle(`${workspace.team.name} · ${workspace.season.name} — BATON`)
    rememberRecentWorkspace(workspace)
    if (!candidateIsActive || handledCandidates.current.has(candidateIdentity)) return

    handledCandidates.current.add(candidateIdentity)
    if (saveAccessKey(teamId, hashAccessKey)) {
      setIgnoredCandidate(candidateIdentity)
      clearFragment()
    }
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
      <>
        <title>{workspaceTitle}</title>
        <main className="remote-state-page">
          <section className="remote-state" role="alert">
            <strong>작업 공간 주소를 확인해 주세요.</strong>
            <p>팀 또는 시즌 정보가 빠져 있습니다.</p>
            <Link to="/" className="primary-button">처음부터 시작하기</Link>
          </section>
        </main>
      </>
    )
  }

  if (!accessKey) {
    return (
      <>
        <title>{workspaceTitle}</title>
        <main className="remote-state-page">
          <section className="remote-state" role="alert">
            <span className="section-kicker">접근 키 필요</span>
            <strong>이 작업 공간을 열 수 없어요.</strong>
            <p>{new URLSearchParams(location.search).has('brief')
              ? '팀 공유 링크로 접근 키를 등록한 뒤 이 브리프 링크를 다시 열어 주세요.'
              : '팀에서 받은 공유 링크로 다시 접속해 주세요.'}</p>
            <Link to="/" className="secondary-button">새 작업 공간 만들기</Link>
          </section>
        </main>
      </>
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
    <>
      <title>{workspaceTitle}</title>
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
    </>
  )
}
