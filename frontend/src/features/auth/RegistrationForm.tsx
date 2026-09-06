import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { registerLocalAccount } from '@/features/auth/api'

export default function RegistrationForm() {
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const registrationMutation = useMutation({
    mutationFn: registerLocalAccount,
  })

  if (registrationMutation.isSuccess) {
    return (
      <div className="auth-result" role="status">
        <span className="auth-result-mark" aria-hidden="true">✓</span>
        <h3>인증 메일을 확인해 주세요.</h3>
        <p>
          가입할 수 있는 이메일이면 인증 메일을 보내드립니다. 메일의 링크에서 비밀번호를 설정하세요.
        </p>
        <Link className="primary-button auth-link-button" to="/login">로그인 화면으로</Link>
      </div>
    )
  }

  return (
    <form
      className="auth-form auth-form-stack"
      onSubmit={(event) => {
        event.preventDefault()
        registrationMutation.mutate({ displayName, email })
      }}
    >
      <label>
        <span>표시 이름</span>
        <input
          autoComplete="name"
          maxLength={100}
          name="displayName"
          required
          value={displayName}
          onChange={(event) => setDisplayName(event.target.value)}
        />
        <small>팀에서 알아볼 수 있는 이름을 입력하세요.</small>
      </label>
      <label>
        <span>이메일</span>
        <input
          autoComplete="email"
          inputMode="email"
          maxLength={320}
          name="email"
          required
          type="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </label>
      {registrationMutation.isError && (
        <p className="form-error" role="alert">
          {registrationMutation.error instanceof Error
            ? registrationMutation.error.message
            : '가입 요청을 처리하지 못했습니다.'}
        </p>
      )}
      <button
        className="primary-button auth-submit"
        disabled={registrationMutation.isPending}
        type="submit"
      >
        {registrationMutation.isPending ? '인증 메일 보내는 중' : '인증 메일 받기'}
      </button>
      <p className="auth-switch-copy">
        이미 계정이 있나요? <Link to="/login">로그인</Link>
      </p>
    </form>
  )
}
