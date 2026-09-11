import { Link, Navigate } from 'react-router-dom'
import { useAuthSession } from '@/features/auth/useAuthSession'
import MyTeamsPanel from '@/features/team-access/MyTeamsPanel'
import '@/features/team-access/my-teams.scss'

export default function MyTeamsPage() {
  const session = useAuthSession()
  if (session.data && !session.data.authenticated) return <Navigate to="/login?returnTo=%2Fmy-teams" replace />
  return <main className="my-teams-page">
    <title>내 팀 — BATON</title>
    <div className="my-teams-content">
      <header><Link className="brand" to="/"><span className="brand-mark" aria-hidden="true" />BATON</Link><h1>내 팀</h1>
        <p>참여한 팀을 선택하고 이어서 작업하세요.</p>
        <nav aria-label="계정 메뉴"><Link to="/">시작 화면</Link><Link to="/account">계정 보안</Link></nav>
      </header>
      {session.isPending ? <p role="status">로그인 상태를 확인하고 있습니다.</p>
        : session.isError ? <div role="alert"><p>로그인 상태를 확인하지 못했습니다.</p>
          <button type="button" className="secondary-button" onClick={() => void session.refetch()}>다시 확인</button></div>
          : session.data.authenticated && <MyTeamsPanel key={session.data.accountId} accountId={session.data.accountId} />}
    </div>
  </main>
}
