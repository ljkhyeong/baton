import AuthPageShell from '@/features/auth/AuthPageShell'
import LoginForm from '@/features/auth/LoginForm'

export default function LoginPage() {
  return (
    <>
      <title>로그인 — BATON</title>
      <AuthPageShell
        eyebrow="ACCOUNT ACCESS"
        title="BATON에 로그인"
        description="로그인 방법을 선택하세요."
      >
        <LoginForm />
      </AuthPageShell>
    </>
  )
}
