import AuthPageShell from '@/features/auth/AuthPageShell'
import EmailVerification from '@/features/auth/EmailVerification'

export default function EmailVerificationPage() {
  return (
    <>
      <title>이메일 인증 — BATON</title>
      <AuthPageShell
        formTitle="비밀번호 설정"
        title="이메일 인증"
        description="사용할 비밀번호를 설정하세요."
      >
        <EmailVerification />
      </AuthPageShell>
    </>
  )
}
