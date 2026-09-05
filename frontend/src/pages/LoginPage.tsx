import AuthPageShell from '@/features/auth/AuthPageShell'
import LoginForm from '@/features/auth/LoginForm'

export default function LoginPage() {
  return (
    <>
      <title>로그인 — BATON</title>
      <AuthPageShell
        eyebrow="ACCOUNT ACCESS"
        title="BATON에 로그인"
        description="사용할 로그인 수단을 선택하세요. 현재 서로 다른 로그인 방법으로 만든 계정은 통합할 수 없습니다."
      >
        <LoginForm />
      </AuthPageShell>
    </>
  )
}
