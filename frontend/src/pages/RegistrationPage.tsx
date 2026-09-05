import AuthPageShell from '@/features/auth/AuthPageShell'
import RegistrationGate from '@/features/auth/RegistrationGate'

export default function RegistrationPage() {
  return (
    <>
      <title>계정 만들기 — BATON</title>
      <AuthPageShell
        formTitle="가입 정보"
        title="계정 만들기"
        description="이름과 이메일을 입력하면 인증 메일을 보내드립니다."
      >
        <RegistrationGate />
      </AuthPageShell>
    </>
  )
}
