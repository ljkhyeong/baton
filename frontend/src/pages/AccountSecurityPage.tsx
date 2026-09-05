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
          formTitle="계정 설정"
          title="내 계정"
          description="로그인 방법과 로그인 상태를 확인합니다."
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
          formTitle="계정 설정"
          title="내 계정"
          description="로그인 방법과 로그인 상태를 확인합니다."
        >
          <div className="auth-result auth-result-warning" role="alert">
            <span className="auth-result-mark" aria-hidden="true">!</span>
            <h3>로그인 상태를 확인하지 못했습니다.</h3>
            <p>로그인 상태를 확인한 뒤 계정을 관리할 수 있습니다. 잠시 후 다시 확인해 주세요.</p>
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
        formTitle="계정 설정"
        title="내 계정"
        description="캘린더 구독, 로그인 방법, 비밀번호를 관리합니다."
      >
        <CalendarSubscriptionList key={`calendars:${sessionQuery.data.accountId}`} accountId={sessionQuery.data.accountId} />
        <AccountSecurityPanel key={`security:${sessionQuery.data.accountId}`} accountId={sessionQuery.data.accountId} />
      </AuthPageShell>
    </>
  )
}
