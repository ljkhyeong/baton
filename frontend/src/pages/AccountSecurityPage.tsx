import { Navigate } from 'react-router-dom'
import CalendarSubscriptionList from '@/features/calendar/CalendarSubscriptionList'
import AccountSecurityPanel from '@/features/auth/AccountSecurityPanel'
import AuthPageShell from '@/features/auth/AuthPageShell'
import { useAuthSession } from '@/features/auth/useAuthSession'

export default function AccountSecurityPage() {
  const sessionQuery = useAuthSession()

  if (sessionQuery.isPending) {
    return (
      <>
        <title>내 계정 — BATON</title>
        <AuthPageShell
          eyebrow="MY ACCOUNT"
          title="내 계정"
          description="연결된 로그인 수단과 계정 세션을 확인합니다."
        >
          <div className="auth-loading" role="status">로그인 상태를 확인하고 있습니다.</div>
        </AuthPageShell>
      </>
    )
  }

  if (sessionQuery.isError && sessionQuery.data === undefined) {
    return (
      <>
        <title>내 계정 — BATON</title>
        <AuthPageShell
          eyebrow="MY ACCOUNT"
          title="내 계정"
          description="연결된 로그인 수단과 계정 세션을 확인합니다."
        >
          <div className="auth-result auth-result-warning" role="alert">
            <span className="auth-result-mark" aria-hidden="true">!</span>
            <h3>로그인 상태를 확인하지 못했습니다.</h3>
            <p>로그아웃으로 판단하지 않았습니다. 잠시 후 다시 확인해 주세요.</p>
            <button
              className="primary-button"
              type="button"
              disabled={sessionQuery.isFetching}
              onClick={() => void sessionQuery.refetch()}
            >
              {sessionQuery.isFetching ? '다시 확인 중' : '로그인 상태 다시 확인'}
            </button>
          </div>
        </AuthPageShell>
      </>
    )
  }

  if (!sessionQuery.data?.authenticated) {
    return <Navigate to="/login?returnTo=%2Faccount" replace />
  }

  return (
    <>
      <title>내 계정 — BATON</title>
      <AuthPageShell
        eyebrow="MY ACCOUNT"
        title="내 계정"
        description="내 캘린더 구독과 로그인 수단을 확인하고 비밀번호와 계정 세션을 관리합니다."
      >
        <CalendarSubscriptionList key={`calendars:${sessionQuery.data.accountId}`} accountId={sessionQuery.data.accountId} />
        <AccountSecurityPanel key={`security:${sessionQuery.data.accountId}`} accountId={sessionQuery.data.accountId} />
      </AuthPageShell>
    </>
  )
}
