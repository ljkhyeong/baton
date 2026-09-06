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
        <h3>지금 가입할 수 있는지 확인하지 못했습니다.</h3>
        <p>잠시 후 다시 확인해 주세요. 기존 계정으로는 로그인할 수 있습니다.</p>
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
        <h3>현재는 이메일로 가입할 수 없습니다.</h3>
        <p>기존 이메일 계정이나 사용 가능한 소셜 계정으로 로그인하세요.</p>
        <Link className="primary-button auth-link-button" to="/login">로그인 화면으로</Link>
      </div>
    )
  }

  return <RegistrationForm />
}
