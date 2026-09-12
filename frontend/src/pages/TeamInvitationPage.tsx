import { useMutation } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { acceptTeamInvitation, previewTeamInvitation, permissionNames } from '@/features/team-access/api'
import { clearTeamInvitationToken, readTeamInvitationToken, storeTeamInvitationToken } from '@/features/team-access/pendingTeamInvitation'
export default function TeamInvitationPage() {
  const location = useLocation()
  return <InvitationPageContent key={`${location.key}:${location.hash}`} />
}

function InvitationPageContent() {
  const session = useAuthSession()
  const [token] = useState(() => readTeamInvitationToken(window.location.hash))
  const [stored, setStored] = useState(false)
  useEffect(() => {
    if (!token) return
    const saved = storeTeamInvitationToken(token)
    setStored(saved)
    if (saved) {
      window.history.replaceState(window.history.state, '', '/join')
    }
  }, [token])
  return <main className="remote-state-page"><title>팀 초대 — BATON</title><section className="remote-state">
    <span className="section-kicker">팀 초대</span><h1>팀 초대 확인</h1>
    {!token ? <p>초대 링크를 다시 열어 주세요. 링크에는 유효 기간이 있습니다.</p>
      : session.isPending ? <p role="status">로그인 상태를 확인하고 있습니다.</p>
        : session.isError ? <p role="alert">{session.error.message} <button type="button" onClick={() => void session.refetch()}>다시 확인</button></p>
          : !session.data?.authenticated ? <>
            <p>초대를 받을 계정으로 로그인한 뒤 팀과 구성원 이름을 확인해 주세요.</p>
            {stored ? <Link to="/login?returnTo=%2Fjoin" className="primary-button">로그인하고 초대 확인</Link>
              : <p>이 브라우저에 초대 링크를 저장하지 못했습니다. 로그인한 뒤 받은 링크를 다시 여세요. <Link to="/login">로그인</Link></p>}
          </> : <InvitationContent key={`${session.data.accountId}:${token}`} accountId={session.data.accountId} token={token} />}
    <Link to="/">처음 화면으로 이동</Link>
  </section></main>
}
function InvitationContent({ accountId, token }: { accountId: string; token: string }) {
  const navigate = useNavigate()
  const preview = useMutation({ mutationFn: () => previewTeamInvitation(accountId, token) })
  useEffect(() => { preview.mutate() }, [accountId, token])
  const accept = useMutation({ mutationFn: () => acceptTeamInvitation(accountId, token, preview.data!), onSuccess: value => {
    clearTeamInvitationToken()
    void navigate(`/teams/${value.teamId}/seasons/${value.seasonId}`, { replace: true })
  } })
  if (preview.isPending || preview.isIdle) return <p role="status">초대 내용을 확인하고 있습니다.</p>
  if (preview.isError) return <div><p role="alert">{preview.error.message} <button type="button" onClick={() => preview.mutate()}>다시 확인</button></p>
    <button type="button" className="secondary-button" onClick={() => {
      clearTeamInvitationToken()
      void navigate('/my-teams', { replace: true })
    }}>이 초대 지우기</button></div>
  const value = preview.data
  return <div>
    <h2>{value.teamName}</h2><p>{value.memberName} 구성원으로 참여합니다. 권한은 {permissionNames[value.permission]}입니다.</p>
    <p>다른 사람의 이름이거나 다른 계정으로 초대받았다면 수락하지 말고 팀 관리자에게 알려 주세요.</p>
    <button type="button" className="primary-button" disabled={accept.isPending} onClick={() => accept.mutate()}>
      {accept.isPending ? '초대 수락 중…' : '이 계정으로 초대 수락'}
    </button>
    {accept.isError && <p role="alert">{accept.error.message}</p>}
  </div>
}
