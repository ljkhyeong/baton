import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useLayoutEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { resetPassword, verifyLocalEmail } from '@/features/auth/api'
import { authSessionQueryKey } from '@/features/auth/useAuthSession'
import { accountMembershipKeys } from '@/features/membership/queries'
import { ApiError } from '@/shared/api/ApiError'

type VerificationState = 'ready' | 'invalid' | 'success'
type PasswordPurpose = 'registration' | 'password-reset'

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : '비밀번호를 저장하지 못했습니다. 다시 시도해 주세요.'
}

export default function EmailPasswordConfirmation({ purpose }: { purpose: PasswordPurpose }) {
  const location = useLocation()
  const [token, setToken] = useState<string | null>(null)

  useLayoutEffect(() => {
    if (!location.hash) return
    setToken(new URLSearchParams(location.hash.slice(1)).get('token'))
    window.history.replaceState(
      window.history.state,
      '',
      `${window.location.pathname}${window.location.search}`,
    )
  }, [location])

  return <EmailPasswordForm key={token} token={token} purpose={purpose} />
}

function EmailPasswordForm({ token, purpose }: { token: string | null; purpose: PasswordPurpose }) {
  const passwordReset = purpose === 'password-reset'
  const queryClient = useQueryClient()
  const [state, setState] = useState<VerificationState>(
    token && token.length >= 32 && token.length <= 512 ? 'ready' : 'invalid',
  )
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [validationError, setValidationError] = useState<string>()
  const verificationMutation = useMutation({
    mutationFn: () => (passwordReset ? resetPassword : verifyLocalEmail)(token ?? '', password),
    onSuccess: async () => {
      setState('success')
      setPassword('')
      setPasswordConfirmation('')
      if (passwordReset) {
        await queryClient.cancelQueries({ queryKey: authSessionQueryKey, exact: true })
        await queryClient.cancelQueries({ queryKey: accountMembershipKeys.all })
        queryClient.setQueryData(authSessionQueryKey, { authenticated: false })
        queryClient.removeQueries({ queryKey: accountMembershipKeys.all })
      }
    },
    onError: (error) => {
      if (error instanceof ApiError
        && error.code === (passwordReset ? 'PASSWORD_RESET_INVALID' : 'EMAIL_VERIFICATION_INVALID')) {
        setState('invalid')
      }
    },
  })

  if (state === 'success') {
    return (
      <div className="auth-result" role="status">
        <span className="auth-result-mark" aria-hidden="true">✓</span>
        <h3>{passwordReset ? '비밀번호를 변경했습니다.' : '이메일 인증을 완료했습니다.'}</h3>
        <p>{passwordReset
          ? '기존 BATON 계정 로그인 세션은 모두 종료됩니다. 새 비밀번호로 다시 로그인해 주세요.'
          : '이제 등록한 이메일과 방금 정한 비밀번호로 BATON에 로그인할 수 있습니다.'}</p>
        <Link className="primary-button auth-link-button" to="/login">로그인하기</Link>
      </div>
    )
  }

  if (state === 'invalid') {
    return (
      <div className="auth-result auth-result-warning" role="alert">
        <span className="auth-result-mark" aria-hidden="true">!</span>
        <h3>{passwordReset ? '재설정 링크를 확인해 주세요.' : '인증 링크를 확인해 주세요.'}</h3>
        <p>
          링크가 올바르지 않거나 만료되었을 수 있습니다. 이미 인증에 사용한 링크도 다시 사용할 수 없습니다.
        </p>
        <p>
          비밀번호를 정한 뒤 결과를 확인하지 못했다면 해당 비밀번호로 먼저 로그인해 보세요.
          {passwordReset ? '변경하지 못했다면 새 재설정 메일을 요청하세요.' : '아직 인증을 완료하지 않았다면 새 인증 메일을 요청하세요.'}
        </p>
        <div className="auth-result-actions">
          <Link className="primary-button auth-link-button" to="/login">로그인하기</Link>
          <Link className="auth-secondary-link" to={passwordReset ? '/forgot-password' : '/register'}>
            {passwordReset ? '재설정 메일 다시 받기' : '인증 메일 다시 받기'}
          </Link>
        </div>
      </div>
    )
  }

  return (
    <form
      className="auth-form auth-form-stack"
      onSubmit={(event) => {
        event.preventDefault()
        setValidationError(undefined)
        if (password !== passwordConfirmation) {
          setValidationError('비밀번호 확인이 일치하지 않습니다.')
          return
        }
        verificationMutation.mutate()
      }}
    >
      <div className="auth-verification-intro" role="status">
        {passwordReset
          ? '새 비밀번호를 저장하면 기존 BATON 계정 로그인 세션이 모두 종료됩니다. 팀 공유 접근 키와 이미 발급된 ROUND 참여 권한은 유지됩니다.'
          : '이 계정에서 사용할 비밀번호를 정해 주세요. 저장하면 이메일 인증도 완료됩니다.'}
      </div>
      <label>
        <span>새 비밀번호</span>
        <input
          autoComplete="new-password"
          maxLength={128}
          minLength={12}
          name="password"
          required
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <small>12자 이상 128자 이하로 입력하세요.</small>
      </label>
      <label>
        <span>새 비밀번호 확인</span>
        <input
          autoComplete="new-password"
          maxLength={128}
          minLength={12}
          name="passwordConfirmation"
          required
          type="password"
          value={passwordConfirmation}
          onChange={(event) => setPasswordConfirmation(event.target.value)}
        />
      </label>
      {(validationError || verificationMutation.isError) && (
        <p className="form-error" role="alert">
          {validationError ?? errorMessage(verificationMutation.error)}
        </p>
      )}
      {passwordReset && verificationMutation.isError && !validationError && (
        <p>결과를 확인하지 못했어도 비밀번호가 변경되었을 수 있습니다. <Link to="/login">입력한 비밀번호로 로그인</Link>하거나 다시 시도해 주세요.</p>
      )}
      <button
        className="primary-button auth-submit"
        disabled={verificationMutation.isPending}
        type="submit"
      >
        {verificationMutation.isPending
          ? (passwordReset ? '비밀번호 변경 중' : '계정 준비 중')
          : (passwordReset ? '비밀번호 변경' : '비밀번호 정하고 인증 완료')}
      </button>
    </form>
  )
}
