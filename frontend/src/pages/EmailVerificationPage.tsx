import AuthPageShell from '@/features/auth/AuthPageShell'
import EmailPasswordConfirmation from '@/features/auth/EmailPasswordConfirmation'

export default function EmailVerificationPage() {
  return (
    <>
      <title>이메일 확인 — BATON</title>
      <AuthPageShell
        eyebrow="EMAIL VERIFICATION"
        title="이메일 주소 확인"
        description="메일로 받은 링크에서 비밀번호를 정하면 가입이 완료됩니다."
      >
        <EmailPasswordConfirmation purpose="registration" />
      </AuthPageShell>
    </>
  )
}
