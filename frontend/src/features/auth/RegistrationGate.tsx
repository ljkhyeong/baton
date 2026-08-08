import { Link } from 'react-router-dom'
import RegistrationForm from '@/features/auth/RegistrationForm'
import { useAuthCapabilities } from '@/features/auth/useAuthCapabilities'

export default function RegistrationGate() {
  const capabilitiesQuery = useAuthCapabilities()

  if (capabilitiesQuery.isPending) {
    return (
      <div className="auth-loading" role="status">
        새 계정을 만들 수 있는지 확인하고 있습니다.
      </div>
    )
  }

  if (capabilitiesQuery.isError) {
    return (
      <div className="auth-result auth-result-warning" role="alert">
        <span className="auth-result-mark" aria-hidden="true">!</span>
        <h3>계정 만들기 상태를 확인하지 못했습니다.</h3>
        <p>안전하게 가입 폼을 열지 않았습니다. 기존 계정 로그인은 계속 사용할 수 있습니다.</p>
        <div className="auth-result-actions">
          <button
            className="primary-button auth-link-button"
            type="button"
            disabled={capabilitiesQuery.isFetching}
            onClick={() => void capabilitiesQuery.refetch()}
          >
            {capabilitiesQuery.isFetching ? '다시 확인 중' : '다시 확인'}
          </button>
          <Link className="auth-secondary-link" to="/login">로그인으로</Link>
        </div>
      </div>
    )
  }

  if (!capabilitiesQuery.data.localRegistrationEnabled) {
    return (
      <div className="auth-result auth-result-warning" role="status">
        <span className="auth-result-mark" aria-hidden="true">!</span>
        <h3>현재 새 자체 이메일 계정을 만들 수 없습니다.</h3>
        <p>기존 자체 이메일 계정과 활성화된 소셜 로그인은 로그인 화면에서 계속 사용할 수 있습니다.</p>
        <Link className="primary-button auth-link-button" to="/login">로그인 화면으로</Link>
      </div>
    )
  }

  return <RegistrationForm />
}
