import { useMutation } from '@tanstack/react-query'
import { useLayoutEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { verifyLocalEmail } from '@/features/auth/api'
import { ApiError } from '@/shared/api/ApiError'

type VerificationState = 'ready' | 'invalid' | 'success'

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : '이메일 인증을 완료하지 못했습니다. 다시 시도해 주세요.'
}

export default function EmailVerification() {
  const [token] = useState(
    () => new URLSearchParams(window.location.hash.slice(1)).get('token'),
  )
  const [state, setState] = useState<VerificationState>(
    token && token.length >= 32 && token.length <= 512 ? 'ready' : 'invalid',
  )
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [validationError, setValidationError] = useState<string>()
  const fragmentRemoved = useRef(false)
  const verificationMutation = useMutation({
    mutationFn: () => verifyLocalEmail(token ?? '', password),
    onSuccess: () => setState('success'),
    onError: (error) => {
      if (error instanceof ApiError && error.code === 'EMAIL_VERIFICATION_INVALID') {
        setState('invalid')
      }
    },
  })

  useLayoutEffect(() => {
    if (fragmentRemoved.current) return
    fragmentRemoved.current = true
    window.history.replaceState(
      window.history.state,
      '',
      `${window.location.pathname}${window.location.search}`,
    )
  }, [])

  if (state === 'success') {
    return (
      <div className="auth-result" role="status">
        <span className="auth-result-mark" aria-hidden="true">✓</span>
        <h3>이메일 인증을 완료했습니다.</h3>
        <p>이제 등록한 이메일과 방금 정한 비밀번호로 BATON에 로그인할 수 있습니다.</p>
        <Link className="primary-button auth-link-button" to="/login">로그인하기</Link>
      </div>
    )
  }

  if (state === 'invalid') {
    return (
      <div className="auth-result auth-result-warning" role="alert">
        <span className="auth-result-mark" aria-hidden="true">!</span>
        <h3>인증 링크를 확인해 주세요.</h3>
        <p>링크가 올바르지 않거나 만료되었습니다. 가입 화면에서 새 인증 메일을 요청하세요.</p>
        <Link className="primary-button auth-link-button" to="/register">인증 메일 다시 받기</Link>
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
        비밀번호를 설정해 이메일 인증을 완료하세요.
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
      <button
        className="primary-button auth-submit"
        disabled={verificationMutation.isPending}
        type="submit"
      >
        {verificationMutation.isPending ? '계정 준비 중' : '비밀번호 정하고 인증 완료'}
      </button>
    </form>
  )
}
