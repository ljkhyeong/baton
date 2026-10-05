import { Navigate } from 'react-router-dom'
import CalendarSubscriptionList from '@/features/calendar/CalendarSubscriptionList'
import AccountSecurityPanel from '@/features/auth/AccountSecurityPanel'
import { useAuthSession } from '@/features/auth/useAuthSession'
import AccountTopbar from '@/features/team-access/AccountTopbar'

export default function AccountSecurityPage() {
  const sessionQuery = useAuthSession()

  if (sessionQuery.data && !sessionQuery.data.authenticated) {
    return <Navigate to="/login?returnTo=%2Faccount" replace />
  }

  return (
    <main className="my-teams-page">
      <title>내 계정 — BATON</title>
      <AccountTopbar />
      <div className="my-teams-content account-content">
        <div className="my-teams-intro">
          <h1>내 계정</h1>
          <p>로그인 방법과 비밀번호, 캘린더 구독을 관리합니다.</p>
        </div>
        {sessionQuery.isPending ? (
          <p className="my-teams-state" role="status">로그인 상태를 확인하고 있습니다.</p>
        ) : sessionQuery.isError && sessionQuery.data === undefined ? (
          <div className="my-teams-state my-teams-state-warning" role="alert">
            <p>로그인 상태를 확인하지 못했습니다. 로그인 상태를 확인한 뒤 계정을 관리할 수 있습니다.</p>
            <button
              className="secondary-button"
              type="button"
              disabled={sessionQuery.isFetching}
              onClick={() => void sessionQuery.refetch()}
            >
              {sessionQuery.isFetching ? '다시 확인 중' : '로그인 상태 다시 확인'}
            </button>
          </div>
        ) : sessionQuery.data && (
          <AccountSecurityPanel key={`security:${sessionQuery.data.accountId}`} accountId={sessionQuery.data.accountId}>
            <CalendarSubscriptionList key={`calendars:${sessionQuery.data.accountId}`} accountId={sessionQuery.data.accountId} />
          </AccountSecurityPanel>
        )}
        <p className="my-teams-note">같은 이메일이어도 로그인 방법이 다르면 별도 계정입니다.</p>
      </div>
    </main>
  )
}
