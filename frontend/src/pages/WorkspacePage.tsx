import {
  useCallback,
  useLayoutEffect,
  useRef,
  useState,
} from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { identityEndpoints } from '@/features/identity/contract'
import {
  useIdentitySessionQuery,
  useTeamMembershipQuery,
} from '@/features/identity/queries'
import WorkspaceApp from '@/features/workspace/WorkspaceApp'
import type { WorkspaceAccess } from '@/features/workspace/api'
import { rememberRecentWorkspace } from '@/features/workspace/storage'
import type { WorkspaceProjection } from '@/features/workspace/types'
import {
  consumePersistedWorkspaceAccessKey,
} from '@/shared/auth/accountScopedState'
import { generateCanonicalUuid } from '@/shared/lib/idempotencyKey'

type LegacyCandidate = Extract<WorkspaceAccess, { mode: 'legacy' }>

function legacyCandidate(accessKey: string): LegacyCandidate | null {
  if (!accessKey) return null
  return {
    mode: 'legacy',
    accessKey,
    cacheIdentity: generateCanonicalUuid(),
  }
}

function replaceAccessKeyFragment(
  location: Pick<Location, 'hash' | 'pathname' | 'search'>,
  accessKey?: string,
) {
  const fragment = accessKey ? `#accessKey=${encodeURIComponent(accessKey)}` : ''
  if (location.hash === fragment) return
  window.history.replaceState(
    window.history.state,
    '',
    `${location.pathname}${location.search}${fragment}`,
  )
}

function WorkspaceRoute({ teamId, seasonId }: { teamId: string; seasonId: string }) {
  const location = useLocation()
  const navigate = useNavigate()
  const sessionQuery = useIdentitySessionQuery()
  const accountId = sessionQuery.data?.authenticated
    ? (sessionQuery.data.accountId ?? '')
    : ''
  const membershipQuery = useTeamMembershipQuery(teamId, accountId)
  const initialCandidatesRef = useRef<{
    primary: LegacyCandidate | null
    fallback: LegacyCandidate | null
    movedFromStorage: boolean
  } | null>(null)

  if (initialCandidatesRef.current === null) {
    const hashAccessKey = new URLSearchParams(
      location.hash.replace(/^#/, ''),
    ).get('accessKey') ?? ''
    const persistedAccessKey = consumePersistedWorkspaceAccessKey(teamId)
    initialCandidatesRef.current = {
      primary: legacyCandidate(hashAccessKey || persistedAccessKey),
      fallback: hashAccessKey && persistedAccessKey && hashAccessKey !== persistedAccessKey
        ? legacyCandidate(persistedAccessKey)
        : null,
      movedFromStorage: Boolean(!hashAccessKey && persistedAccessKey),
    }
  }

  const initialCandidates = initialCandidatesRef.current
  const [legacyAccess, setLegacyAccess] = useState(initialCandidates.primary)
  const [legacyFallback, setLegacyFallback] = useState(initialCandidates.fallback)
  const sessionMembership = Boolean(
    accountId
    && membershipQuery.data
    && membershipQuery.data.accountId === accountId
    && membershipQuery.data.teamId === teamId,
  )
  useLayoutEffect(() => {
    if (sessionMembership) {
      replaceAccessKeyFragment(window.location)
      setLegacyAccess(null)
      setLegacyFallback(null)
      return
    }
    if (initialCandidates.movedFromStorage && legacyAccess) {
      replaceAccessKeyFragment(window.location, legacyAccess.accessKey)
    }
  }, [initialCandidates.movedFromStorage, legacyAccess, sessionMembership])

  const handleWorkspaceLoaded = useCallback((workspace: WorkspaceProjection) => {
    if (!sessionMembership) return
    rememberRecentWorkspace(accountId, workspace)
  }, [accountId, sessionMembership])

  const reopenWithStoredKey = () => {
    if (!legacyFallback) return
    setLegacyAccess(legacyFallback)
    setLegacyFallback(null)
    replaceAccessKeyFragment(window.location, legacyFallback.accessKey)
  }

  const navigateToSeason = useCallback((
    nextSeasonId: string,
    currentLegacyAccessKey?: string,
  ) => {
    const fragment = currentLegacyAccessKey
      ? `#accessKey=${encodeURIComponent(currentLegacyAccessKey)}`
      : ''
    void navigate(
      `/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(nextSeasonId)}${fragment}`,
    )
  }, [navigate, teamId])

  const sessionResolutionPending = sessionQuery.isPending
    || Boolean(accountId && membershipQuery.isPending)
  if (sessionResolutionPending) {
    return (
      <main className="remote-state-page">
        <section className="remote-state" aria-live="polite" aria-busy="true">
          <strong>계정과 작업 공간 권한을 확인하고 있어요.</strong>
          <p>로그인한 구성원 자리와 현재 팀을 안전하게 연결하고 있습니다.</p>
        </section>
      </main>
    )
  }

  const access: WorkspaceAccess | null = sessionMembership
    ? {
        mode: 'session',
        accountId,
      }
    : legacyAccess

  if (!access) {
    return (
      <main className="remote-state-page">
        <section className="remote-state" role="alert">
          <span className="section-kicker">구성원 연결 필요</span>
          <strong>이 작업 공간을 열 수 없어요.</strong>
          <p>
            로그인한 계정을 이 팀의 구성원 초대에 연결하거나, 이전 파일럿 공유 링크로
            다시 접속해 주세요.
          </p>
          {!sessionQuery.data?.authenticated && sessionQuery.data?.oidcEnabled && (
            <a className="primary-button" href={identityEndpoints.googleAuthorization}>
              Google로 로그인
            </a>
          )}
          <Link to="/" className="secondary-button">계정·초대 확인하기</Link>
        </section>
      </main>
    )
  }

  const storedKeyFallback = access.mode === 'legacy' && legacyFallback
    ? (
        <div className="workspace-key-fallback">
          <p>이 브라우저에서 한 번만 가져온 이전 접근 키가 있습니다.</p>
          <button type="button" className="primary-button" onClick={reopenWithStoredKey}>
            이전 키로 다시 열기
          </button>
        </div>
      )
    : undefined

  const authCacheIdentity = access.mode === 'session'
    ? `account:${access.accountId}`
    : `legacy:${access.cacheIdentity}`

  return (
    <WorkspaceApp
      key={`${teamId}:${seasonId}:${authCacheIdentity}`}
      teamId={teamId}
      seasonId={seasonId}
      access={access}
      accessDeniedAction={storedKeyFallback}
      onWorkspaceLoaded={handleWorkspaceLoaded}
      onSelectSeason={navigateToSeason}
      onSeasonCreated={navigateToSeason}
    />
  )
}

export default function WorkspacePage() {
  const { teamId = '', seasonId = '' } = useParams()

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

  return <WorkspaceRoute key={`${teamId}:${seasonId}`} teamId={teamId} seasonId={seasonId} />
}
