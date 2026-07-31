import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { identityEndpoints } from './contract'
import {
  useAcceptInvitationMutation,
  useIdentitySessionQuery,
  useInvitationPreviewMutation,
  useLogoutSessionMutation,
} from './queries'
import {
  csrfCredential,
  formatIdentityInstant,
  identityErrorMessage,
} from './presentation'

export default function IdentityHomePanel() {
  const sessionQuery = useIdentitySessionQuery()
  const credential = csrfCredential(sessionQuery.data)
  const previewMutation = useInvitationPreviewMutation()
  const acceptMutation = useAcceptInvitationMutation()
  const logoutMutation = useLogoutSessionMutation()
  const [token, setToken] = useState('')
  const [validationMessage, setValidationMessage] = useState('')
  const [statusMessage, setStatusMessage] = useState('')
  const statusRef = useRef<HTMLParagraphElement>(null)
  const activeAccountId = sessionQuery.data?.authenticated
    ? sessionQuery.data.accountId
    : null
  const activeAccountIdRef = useRef(activeAccountId)
  const previousAccountIdRef = useRef(activeAccountId)
  const intentionalLogoutRef = useRef(false)
  activeAccountIdRef.current = activeAccountId

  useEffect(() => {
    const previousAccountId = previousAccountIdRef.current
    previousAccountIdRef.current = activeAccountId
    if (previousAccountId === activeAccountId) return
    const preserveLogoutMessage = Boolean(
      intentionalLogoutRef.current
      && previousAccountId
      && activeAccountId === null,
    )
    intentionalLogoutRef.current = false
    setToken('')
    setValidationMessage('')
    if (!preserveLogoutMessage) setStatusMessage('')
    previewMutation.reset()
    acceptMutation.reset()
  }, [activeAccountId])

  useEffect(() => {
    if (!statusMessage) return
    requestAnimationFrame(() => statusRef.current?.focus())
  }, [statusMessage])

  const submitPreview = (event: FormEvent) => {
    event.preventDefault()
    setValidationMessage('')
    setStatusMessage('')
    acceptMutation.reset()
    if (!sessionQuery.data?.authenticated) {
      setValidationMessage('초대를 확인하려면 먼저 Google로 로그인해 주세요.')
      return
    }
    if (!credential) {
      setValidationMessage('로그인 세션을 다시 확인해 주세요.')
      return
    }
    const normalizedToken = token.trim()
    if (!normalizedToken) {
      setValidationMessage('받은 초대 토큰을 입력해 주세요.')
      return
    }
    const requestedByAccountId = activeAccountId
    void previewMutation.mutateAsync(normalizedToken)
      .then(() => {
        if (activeAccountIdRef.current !== requestedByAccountId) {
          previewMutation.reset()
        }
      })
      .catch(() => {
        // Mutation feedback is rendered below.
      })
  }

  const acceptPreviewedInvitation = async () => {
    const normalizedToken = token.trim()
    if (!normalizedToken || !previewMutation.data) return
    const requestedByAccountId = activeAccountId
    setValidationMessage('')
    setStatusMessage('')
    try {
      const accepted = await acceptMutation.mutateAsync(normalizedToken)
      if (activeAccountIdRef.current !== requestedByAccountId) {
        previewMutation.reset()
        acceptMutation.reset()
        return
      }
      setToken('')
      previewMutation.reset()
      acceptMutation.reset()
      setStatusMessage(
        `${accepted.role === 'OWNER' ? '소유자' : '구성원'} 계정 연결을 완료했습니다.`,
      )
    } catch {
      // Mutation feedback is rendered below.
    }
  }

  const logout = async () => {
    setStatusMessage('')
    intentionalLogoutRef.current = true
    try {
      await logoutMutation.mutateAsync()
      setToken('')
      previewMutation.reset()
      acceptMutation.reset()
      setStatusMessage('로그아웃했습니다. 계정에 연결된 작업 공간 화면도 정리했습니다.')
    } catch {
      intentionalLogoutRef.current = false
      // Mutation feedback is rendered below.
    }
  }

  const session = sessionQuery.data
  const feedbackError =
    validationMessage
    || (sessionQuery.error ? identityErrorMessage(sessionQuery.error) : '')
    || (previewMutation.error ? identityErrorMessage(previewMutation.error) : '')
    || (acceptMutation.error ? identityErrorMessage(acceptMutation.error) : '')
    || (logoutMutation.error ? identityErrorMessage(logoutMutation.error) : '')

  return (
    <section className="identity-home" aria-labelledby="identity-home-title">
      <div className="identity-home-heading">
        <div>
          <span className="section-kicker">계정 · 초대</span>
          <h3 id="identity-home-title">내 구성원 자리를 연결해요</h3>
        </div>
        {session?.authenticated && (
          <button
            type="button"
            className="text-button"
            onClick={() => void logout()}
            disabled={logoutMutation.isPending}
          >
            {logoutMutation.isPending ? '로그아웃 중…' : '로그아웃'}
          </button>
        )}
      </div>

      {sessionQuery.isPending && (
        <p className="identity-session-state" aria-live="polite">로그인 상태를 확인하고 있어요.</p>
      )}

      {!sessionQuery.isPending && session && (
        <div className="identity-session-row">
          <span className={`identity-session-dot ${session.authenticated ? 'active' : ''}`} />
          <span>
            <strong>{session.authenticated ? 'Google 계정 연결됨' : '로그인이 필요해요'}</strong>
            <small>
              {session.authenticated
                ? '받은 초대를 확인하고 내 계정에 연결할 수 있습니다.'
                : '초대 토큰은 로그인 후에만 미리 보고 수락할 수 있습니다.'}
            </small>
          </span>
          {!session.authenticated && session.oidcEnabled && (
            <a
              className="secondary-button identity-login-link"
              href={identityEndpoints.googleAuthorization}
            >
              Google로 로그인
            </a>
          )}
        </div>
      )}

      {!sessionQuery.isPending
        && session
        && !session.authenticated
        && !session.oidcEnabled && (
          <p className="identity-session-state">
            현재 환경에서는 Google 로그인이 비활성화되어 있습니다.
          </p>
        )}

      <form className="identity-token-form" onSubmit={submitPreview}>
        <label htmlFor="identity-invitation-token">
          <span>초대 토큰</span>
          <input
            id="identity-invitation-token"
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={token}
            onChange={(event) => {
              setToken(event.target.value)
              setValidationMessage('')
              setStatusMessage('')
              previewMutation.reset()
              acceptMutation.reset()
            }}
            placeholder="소유자에게 받은 토큰"
            disabled={!session?.authenticated || previewMutation.isPending || acceptMutation.isPending}
          />
          <small>토큰은 이 입력 화면에서만 사용하며 URL이나 브라우저 저장소에 남기지 않습니다.</small>
        </label>
        <button
          type="submit"
          className="secondary-button"
          disabled={!session?.authenticated || previewMutation.isPending || acceptMutation.isPending}
        >
          {previewMutation.isPending ? '초대 확인 중…' : '초대 내용 확인'}
        </button>
      </form>

      {previewMutation.data && (
        <div className="identity-preview" aria-live="polite">
          <div>
            <span>{previewMutation.data.teamName}</span>
            <strong>{previewMutation.data.memberName}</strong>
            <small>
              {previewMutation.data.role === 'OWNER' ? '소유자' : '구성원'}
              {' · '}
              {formatIdentityInstant(previewMutation.data.expiresAt)}까지
            </small>
          </div>
          {previewMutation.data.alreadyAccepted && (
            <p>이미 이 계정에 연결된 초대입니다. 아래에서 연결 상태를 다시 확인할 수 있어요.</p>
          )}
          <button
            type="button"
            className="primary-button"
            onClick={() => void acceptPreviewedInvitation()}
            disabled={acceptMutation.isPending}
          >
            {acceptMutation.isPending
              ? '연결 중…'
              : previewMutation.data.alreadyAccepted
                ? '연결 상태 확인'
                : '이 구성원으로 연결'}
          </button>
        </div>
      )}

      {feedbackError && <p className="form-error" role="alert">{feedbackError}</p>}
      {statusMessage && (
        <p
          ref={statusRef}
          className="form-retry-notice"
          role="status"
          aria-live="polite"
          tabIndex={-1}
        >
          {statusMessage}
        </p>
      )}
    </section>
  )
}
