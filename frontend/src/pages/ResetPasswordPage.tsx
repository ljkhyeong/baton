import AuthPageShell from '@/features/auth/AuthPageShell'
import EmailPasswordConfirmation from '@/features/auth/EmailPasswordConfirmation'

export default function ResetPasswordPage() {
  return (
    <>
      <title>비밀번호 재설정 — BATON</title>
      <AuthPageShell
        eyebrow="계정 복구"
        title="새 비밀번호 설정"
        description="다른 서비스에서 사용하지 않는 비밀번호를 정해 주세요."
      >
        <EmailPasswordConfirmation purpose="password-reset" />
      </AuthPageShell>
    </>
  )
}
