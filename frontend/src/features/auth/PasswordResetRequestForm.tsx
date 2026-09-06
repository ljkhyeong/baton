import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { requestPasswordReset } from '@/features/auth/api'
import { useAuthCapabilities } from '@/features/auth/useAuthCapabilities'

export default function PasswordResetRequestForm() {
  const capabilities = useAuthCapabilities()
  const [email, setEmail] = useState('')
  const mutation = useMutation({ mutationFn: () => requestPasswordReset(email) })

  if (mutation.isSuccess) {
    return (
      <div className="auth-result" role="status">
        <span className="auth-result-mark" aria-hidden="true">✓</span>
        <h3>재설정 요청을 접수했습니다.</h3>
        <p>인증된 이메일 계정이라면 재설정 메일을 보내드립니다. 스팸함도 확인해 주세요.</p>
        <p>링크는 요청 후 30분 동안 한 번만 사용할 수 있습니다. 아직 유효한 링크가 있다면 기존 메일을 사용해 주세요.</p>
        <p>Google·Naver 계정은 해당 서비스에서 비밀번호를 변경해 주세요.</p>
        <Link className="primary-button auth-link-button" to="/login">로그인으로</Link>
      </div>
    )
  }

  if (capabilities.isPending) {
    return <div className="auth-loading" role="status">비밀번호 재설정이 가능한지 확인하고 있습니다.</div>
  }
  if (capabilities.isError) {
    return (
      <div className="auth-result auth-result-warning" role="alert">
        <h3>비밀번호 재설정 상태를 확인하지 못했습니다.</h3>
        <div className="auth-result-actions">
          <button
            className="primary-button"
            type="button"
            disabled={capabilities.isFetching}
            onClick={() => void capabilities.refetch()}
          >
            다시 확인
          </button>
          <Link className="auth-secondary-link" to="/login">로그인으로</Link>
        </div>
      </div>
    )
  }
  if (!capabilities.data.passwordResetEnabled) {
    return (
      <div className="auth-result auth-result-warning" role="status">
        <h3>현재 재설정 메일을 요청할 수 없습니다.</h3>
        <p>이미 받은 유효한 링크는 계속 사용할 수 있습니다. 잠시 후 다시 확인해 주세요.</p>
        <Link className="primary-button auth-link-button" to="/login">로그인으로</Link>
      </div>
    )
  }

  return (
    <form
      className="auth-form auth-form-stack"
      onSubmit={(event) => {
        event.preventDefault()
        mutation.mutate()
      }}
    >
      <label>
        <span>가입한 이메일</span>
        <input
          autoComplete="email"
          inputMode="email"
          name="email"
          type="email"
          required
          maxLength={320}
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </label>
      <p>이메일로 가입한 계정의 비밀번호만 바꿀 수 있습니다. Google·Naver 계정은 해당 서비스에서 변경해 주세요.</p>
      {mutation.isError && (
        <p className="form-error" role="alert">
          {mutation.error instanceof Error ? mutation.error.message : '요청 결과를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.'}
        </p>
      )}
      <button className="primary-button auth-submit" type="submit" disabled={mutation.isPending}>
        {mutation.isPending ? '메일 요청 중' : '재설정 메일 받기'}
      </button>
      <Link className="auth-secondary-link" to="/login">로그인으로</Link>
    </form>
  )
}
