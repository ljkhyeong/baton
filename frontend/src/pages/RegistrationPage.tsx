import AuthPageShell from '@/features/auth/AuthPageShell'
import RegistrationForm from '@/features/auth/RegistrationForm'

export default function RegistrationPage() {
  return (
    <AuthPageShell
      eyebrow="LOCAL ACCOUNT"
      title="자체 이메일 계정 만들기"
      description="이메일 소유를 확인한 화면에서 비밀번호를 정한 뒤 로그인할 수 있습니다."
    >
      <RegistrationForm />
    </AuthPageShell>
  )
}
