import AuthPageShell from '@/features/auth/AuthPageShell'
import LoginForm from '@/features/auth/LoginForm'

export default function LoginPage() {
  return (
    <>
      <title>로그인 — BATON</title>
      <AuthPageShell
        title="로그인"
        description="가입할 때 사용한 방법으로 로그인하세요."
      >
        <LoginForm />
      </AuthPageShell>
    </>
  )
}
