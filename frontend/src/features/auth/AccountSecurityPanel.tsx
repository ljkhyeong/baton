import { useMutation } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import {
  changeLocalPassword,
  deactivateAccount,
  revokeAccountSessions,
} from '@/features/auth/api'
import { useAccountSecurity } from '@/features/auth/useAccountSecurity'

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
  const accountQuery = useAccountSecurity(accountId)
  const [deactivationConfirmed, setDeactivationConfirmed] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [newPasswordConfirmation, setNewPasswordConfirmation] = useState('')
  const [validationError, setValidationError] = useState('')

  const finishAccountSession = (notice: 'password_changed' | 'sessions_revoked' | 'account_deactivated') => {
    window.location.replace(`/login?${new URLSearchParams({ accountNotice: notice })}`)
  }

  const passwordMutation = useMutation({
    mutationFn: () => changeLocalPassword({
      currentPassword,
      newPassword,
    }),
    onSuccess: () => finishAccountSession('password_changed'),
  })

  const sessionRevocationMutation = useMutation({
    mutationFn: revokeAccountSessions,
    onSuccess: () => finishAccountSession('sessions_revoked'),
  })

  const deactivationMutation = useMutation({
    mutationFn: () => deactivateAccount({ expectedAccountId: accountId }),
    onSuccess: () => finishAccountSession('account_deactivated'),
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
    || deactivationMutation.isPending

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

      <section className="account-security-card" aria-labelledby="account-deactivation-title">
        <header><h3 id="account-deactivation-title">계정 비활성화</h3>
          <p>로그인과 계정 권한으로 팀에 접근하는 것을 중지하고 모든 기기에서 로그아웃합니다. 다시 로그인하거나 직접 활성화할 수 없습니다.</p>
        </header>
        <p className="account-security-note">팀의 결정·자료·작성자 기록과 로그인 정보는 보존합니다. 구성원의 활동 상태와 팀 공유 접근 키는 별도로 관리합니다. 개인 캘린더 구독은 해지를 요청하며 외부 서비스의 처리가 끝날 때까지 기존 일정이 보일 수 있습니다.</p>
        <p>팀의 마지막 관리자라면 <Link to="/my-teams">내 팀</Link>에서 다른 활성 관리자를 먼저 지정해 주세요.</p>
        <form className="auth-form" onSubmit={event => {
          event.preventDefault()
          if (deactivationConfirmed && !mutationPending && !accountQuery.isFetching) deactivationMutation.mutate()
        }}>
          <label className="account-deactivation-confirm"><input type="checkbox" required checked={deactivationConfirmed}
            disabled={mutationPending} onChange={event => setDeactivationConfirmed(event.target.checked)} />기록 보존과 로그인 중지를 확인했습니다.</label>
          {deactivationMutation.isError && <p role="alert" className="form-error">{errorMessage(deactivationMutation.error)}</p>}
          <button type="submit" className="danger-button" disabled={!deactivationConfirmed || mutationPending || accountQuery.isFetching}>
            {deactivationMutation.isPending ? '계정 비활성화 중…' : '이 계정 비활성화'}
          </button>
        </form>
      </section>

      <Link className="auth-secondary-link account-back-link" to="/">시작 화면으로 돌아가기</Link>
    </div>
  )
}
