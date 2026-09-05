import AuthPageShell from '@/features/auth/AuthPageShell'
import RegistrationGate from '@/features/auth/RegistrationGate'

export default function RegistrationPage() {
  return (
    <>
      <title>계정 만들기 — BATON</title>
      <AuthPageShell
        eyebrow="LOCAL ACCOUNT"
        title="이메일로 가입"
        description="이메일 소유를 확인한 화면에서 비밀번호를 정한 뒤 로그인할 수 있습니다."
      >
        <RegistrationGate />
      </AuthPageShell>
    </>
  )
}
