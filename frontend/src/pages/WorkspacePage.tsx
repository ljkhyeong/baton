import { useEffect } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import WorkspaceApp from '@/features/workspace/WorkspaceApp'
import { readAccessKey, saveAccessKey } from '@/features/workspace/api'

export default function WorkspacePage() {
  const { teamId = '', seasonId = '' } = useParams()
  const location = useLocation()
  const hashAccessKey = new URLSearchParams(location.hash.replace(/^#/, '')).get('accessKey') ?? ''
  const accessKey = hashAccessKey || readAccessKey(teamId)

  useEffect(() => {
    if (!teamId || !hashAccessKey) return
    if (saveAccessKey(teamId, hashAccessKey)) {
      window.history.replaceState(null, '', `${location.pathname}${location.search}`)
    }
  }, [hashAccessKey, location.pathname, location.search, teamId])

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

  return <WorkspaceApp teamId={teamId} seasonId={seasonId} accessKey={accessKey} />
}
