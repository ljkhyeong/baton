import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  changeLocalPassword,
  revokeAccountSessions,
} from '@/features/auth/api'
import { useAccountSecurity } from '@/features/auth/useAccountSecurity'
import { authSessionQueryKey } from '@/features/auth/useAuthSession'
import { accountMembershipKeys } from '@/features/membership/queries'

const identityLabels = {
  google: 'Google',
  naver: 'Naver',
  local_email: '자체 이메일',
} as const

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : '계정 보안 요청을 처리하지 못했습니다.'
}

export default function AccountSecurityPanel({ accountId }: { accountId: string }) {
  const queryClient = useQueryClient()
  const accountQuery = useAccountSecurity(accountId)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [newPasswordConfirmation, setNewPasswordConfirmation] = useState('')
  const [validationError, setValidationError] = useState('')

  const finishAccountSession = async (notice: 'password_changed' | 'sessions_revoked') => {
    await queryClient.cancelQueries({ queryKey: authSessionQueryKey, exact: true })
    await queryClient.cancelQueries({ queryKey: accountMembershipKeys.all })
    queryClient.removeQueries({ queryKey: accountMembershipKeys.all })
    queryClient.removeQueries({ queryKey: ['auth', 'account'] })
    window.location.replace(`/login?${new URLSearchParams({ accountNotice: notice })}`)
  }

  const passwordMutation = useMutation({
    mutationFn: () => changeLocalPassword({
      currentPassword,
      newPassword,
    }),
    onSuccess: async () => {
      setCurrentPassword('')
      setNewPassword('')
      setNewPasswordConfirmation('')
      await finishAccountSession('password_changed')
    },
  })

  const sessionRevocationMutation = useMutation({
    mutationFn: revokeAccountSessions,
    onSuccess: () => finishAccountSession('sessions_revoked'),
  })

  if (accountQuery.isPending) {
    return <div className="auth-loading" role="status">계정 보안 정보를 불러오고 있습니다.</div>
  }

  if (accountQuery.isError) {
    return (
      <div className="auth-result auth-result-warning" role="alert">
        <span className="auth-result-mark" aria-hidden="true">!</span>
        <h3>계정 보안 정보를 불러오지 못했습니다.</h3>
        <p>{errorMessage(accountQuery.error)}</p>
        <div className="auth-result-actions">
          <button
            className="primary-button"
            type="button"
            disabled={accountQuery.isFetching}
            onClick={() => void accountQuery.refetch()}
          >
            {accountQuery.isFetching ? '다시 확인 중' : '다시 확인'}
          </button>
          <Link className="auth-secondary-link" to="/">시작 화면으로</Link>
        </div>
      </div>
    )
  }

  const localIdentity = accountQuery.data.identities.find(
    (identity) => identity.provider === 'local_email',
  )
  const mutationPending = passwordMutation.isPending
    || sessionRevocationMutation.isPending

  return (
    <div className="account-security-stack">
      <section className="account-summary-card" aria-labelledby="account-summary-title">
        <span className="auth-status-dot" aria-hidden="true" />
        <div>
          <h3 id="account-summary-title">{accountQuery.data.displayName}</h3>
          <p>계정 ID {accountQuery.data.accountId}</p>
        </div>
        <ul className="account-identity-list" aria-label="연결된 로그인 수단">
          {accountQuery.data.identities.map((identity) => (
            <li key={identity.provider}>
              <strong>{identityLabels[identity.provider]}</strong>
              <span>{identity.email ?? '이메일을 제공하지 않은 로그인 수단'}</span>
              {identity.email && (
                <small>{identity.emailVerified ? '확인된 이메일' : '확인되지 않은 이메일'}</small>
              )}
            </li>
          ))}
        </ul>
      </section>

      {localIdentity && (
        <section className="account-security-card" aria-labelledby="password-change-title">
          <header>
            <span className="section-kicker">PASSWORD</span>
            <h3 id="password-change-title">비밀번호 변경</h3>
            <p>현재 비밀번호를 확인한 뒤 새 비밀번호를 저장합니다. 완료하면 모든 계정 세션이 종료됩니다.</p>
          </header>
          <form
            className="auth-form"
            onSubmit={(event) => {
              event.preventDefault()
              setValidationError('')
              if (newPassword !== newPasswordConfirmation) {
                setValidationError('새 비밀번호 확인이 일치하지 않습니다.')
                return
              }
              passwordMutation.mutate()
            }}
          >
            <label>
              <span>현재 비밀번호</span>
              <input
                autoComplete="current-password"
                maxLength={128}
                name="currentPassword"
                required
                type="password"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              />
            </label>
            <label>
              <span>새 비밀번호</span>
              <input
                autoComplete="new-password"
                maxLength={128}
                minLength={12}
                name="newPassword"
                required
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
              />
              <small>12자 이상 128자 이하로 입력하세요.</small>
            </label>
            <label>
              <span>새 비밀번호 확인</span>
              <input
                autoComplete="new-password"
                maxLength={128}
                minLength={12}
                name="newPasswordConfirmation"
                required
                type="password"
                value={newPasswordConfirmation}
                onChange={(event) => setNewPasswordConfirmation(event.target.value)}
              />
            </label>
            {(validationError || passwordMutation.isError) && (
              <p className="form-error" role="alert">
                {validationError || errorMessage(passwordMutation.error)}
              </p>
            )}
            <button
              className="primary-button auth-submit"
              disabled={mutationPending}
              type="submit"
            >
              {passwordMutation.isPending ? '비밀번호 변경 중' : '비밀번호 변경'}
            </button>
          </form>
        </section>
      )}

      <section className="account-security-card account-session-card" aria-labelledby="session-revocation-title">
        <header>
          <span className="section-kicker">SESSIONS</span>
          <h3 id="session-revocation-title">모든 기기에서 로그아웃</h3>
          <p>현재 브라우저를 포함해 이 계정으로 로그인한 모든 기존 세션을 종료합니다.</p>
        </header>
        <p className="account-security-note">
          팀 공유 접근 키와 이미 발급된 ROUND 참여권은 계정 세션과 별도이므로 유지됩니다.
        </p>
        {sessionRevocationMutation.isError && (
          <p className="form-error" role="alert">
            {errorMessage(sessionRevocationMutation.error)}
          </p>
        )}
        <button
          className="danger-button"
          disabled={mutationPending}
          type="button"
          onClick={() => {
            if (!window.confirm('이 계정의 모든 기기에서 로그아웃할까요?')) return
            sessionRevocationMutation.mutate()
          }}
        >
          {sessionRevocationMutation.isPending
            ? '모든 세션 종료 중'
            : '모든 기기에서 로그아웃'}
        </button>
      </section>

      <Link className="auth-secondary-link account-back-link" to="/">시작 화면으로 돌아가기</Link>
    </div>
  )
}
