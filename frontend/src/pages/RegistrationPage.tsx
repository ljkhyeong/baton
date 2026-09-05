import AuthPageShell from '@/features/auth/AuthPageShell'
import RegistrationGate from '@/features/auth/RegistrationGate'

export default function RegistrationPage() {
  return (
    <>
      <title>계정 만들기 — BATON</title>
      <AuthPageShell
        eyebrow="LOCAL ACCOUNT"
        title="이메일로 가입"
        description="인증 메일의 링크에서 비밀번호를 설정하면 가입이 완료됩니다."
      >
        <RegistrationGate />
      </AuthPageShell>
    </>
  )
}
