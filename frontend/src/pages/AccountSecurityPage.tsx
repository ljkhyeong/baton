import { Navigate } from 'react-router-dom'
import AccountSecurityPanel from '@/features/auth/AccountSecurityPanel'
import AuthPageShell from '@/features/auth/AuthPageShell'
import { useAuthSession } from '@/features/auth/useAuthSession'

export default function AccountSecurityPage() {
  const sessionQuery = useAuthSession()

  if (sessionQuery.isPending || sessionQuery.isFetching) {
    return (
      <>
        <title>계정 보안 — BATON</title>
        <AuthPageShell
          eyebrow="ACCOUNT SECURITY"
          title="계정 보안"
          description="연결된 로그인 수단과 계정 세션을 확인합니다."
        >
          <div className="auth-loading" role="status">로그인 상태를 확인하고 있습니다.</div>
        </AuthPageShell>
      </>
    )
  }

  if (!sessionQuery.data?.authenticated) {
    return <Navigate to="/login?returnTo=%2Faccount" replace />
  }

  return (
    <>
      <title>계정 보안 — BATON</title>
      <AuthPageShell
        eyebrow="ACCOUNT SECURITY"
        title="계정 보안"
        description="로그인 수단을 확인하고 비밀번호와 모든 기기의 계정 세션을 관리합니다."
      >
        <AccountSecurityPanel accountId={sessionQuery.data.accountId} />
      </AuthPageShell>
    </>
  )
}
