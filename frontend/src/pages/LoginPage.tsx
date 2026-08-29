import AuthPageShell from '@/features/auth/AuthPageShell'
import LoginForm from '@/features/auth/LoginForm'

export default function LoginPage() {
  return (
    <>
      <title>로그인 — BATON</title>
      <AuthPageShell
        eyebrow="ACCOUNT ACCESS"
        title="BATON에 로그인"
        description="사용할 로그인 수단을 선택하세요. 공급자 간 계정 연결은 안전한 재인증 흐름을 마련한 뒤 제공합니다."
      >
        <LoginForm />
      </AuthPageShell>
    </>
  )
}
