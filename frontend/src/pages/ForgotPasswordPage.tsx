import AuthPageShell from '@/features/auth/AuthPageShell'
import PasswordResetRequestForm from '@/features/auth/PasswordResetRequestForm'

export default function ForgotPasswordPage() {
  return (
    <>
      <title>비밀번호 찾기 — BATON</title>
      <AuthPageShell
        formTitle="이메일 입력"
        title="비밀번호를 잊으셨나요?"
        description="가입한 이메일로 새 비밀번호를 설정할 수 있는 링크를 보내드립니다."
      >
        <PasswordResetRequestForm />
      </AuthPageShell>
    </>
  )
}
