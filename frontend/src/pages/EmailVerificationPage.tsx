import AuthPageShell from '@/features/auth/AuthPageShell'
import EmailVerification from '@/features/auth/EmailVerification'

export default function EmailVerificationPage() {
  return (
    <>
      <title>이메일 확인 — BATON</title>
      <AuthPageShell
        eyebrow="EMAIL VERIFICATION"
        title="이메일 주소 확인"
        description="인증 token은 확인 직후 주소창에서 제거하고 서버에 한 번만 전송합니다."
      >
        <EmailVerification />
      </AuthPageShell>
    </>
  )
}
