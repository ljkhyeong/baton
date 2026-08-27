import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import {
  createLocalSession,
  deleteAuthSession,
} from '@/features/auth/api'
import { useAuthCapabilities } from '@/features/auth/useAuthCapabilities'
import {
  clearRememberedAuthReturnTo,
  isRoundRoomAuthReturnTo,
  readRememberedAuthReturnTo,
  rememberAuthReturnTo,
  safeAuthReturnTo,
} from '@/features/auth/returnTo'
import {
  authSessionQueryKey,
  authSessionQueryOptions,
  useAuthSession,
} from '@/features/auth/useAuthSession'
import { accountMembershipKeys } from '@/features/membership/queries'
import { clearAllWorkspaceDeviceState } from '@/features/workspace/deviceState'
import { workspaceKeys } from '@/features/workspace/queries'

const providerLabels = {
  google: 'Google로 계속하기',
  naver: 'Naver로 계속하기',
} as const

const oauthCallbackErrorMessages = {
  login_failed: {
    title: '소셜 로그인을 완료하지 못했습니다.',
    detail: '다시 시도하거나 다른 로그인 수단을 선택해 주세요.',
  },
  temporarily_unavailable: {
    title: '현재 인증 요청을 처리할 수 없습니다.',
    detail: '잠시 후 다시 시도해 주세요.',
  },
} as const

function oauthCallbackErrorMessage(search: string) {
  const errorCode = new URLSearchParams(search).get('oauthError')
  if (errorCode !== 'login_failed' && errorCode !== 'temporarily_unavailable') {
    return null
  }
  return oauthCallbackErrorMessages[errorCode]
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '로그인 요청을 처리하지 못했습니다.'
}

export default function LoginForm() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const sessionQuery = useAuthSession()
  const capabilitiesQuery = useAuthCapabilities()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [deviceStateCleanupError, setDeviceStateCleanupError] = useState('')
  const [oauthCallbackError] = useState(() => (
    oauthCallbackErrorMessage(location.search)
  ))
  const authenticationReturnStarted = useRef(false)
  const requestedReturnTo = new URLSearchParams(location.search).get('returnTo')
  const safeRequestedReturnTo = safeAuthReturnTo(requestedReturnTo)
  const returnTo = safeRequestedReturnTo
    ?? (requestedReturnTo === null ? readRememberedAuthReturnTo() : null)
    ?? '/'
  const returnAfterAuthentication = useCallback(() => {
    if (authenticationReturnStarted.current) return
    authenticationReturnStarted.current = true
    clearRememberedAuthReturnTo()
    if (isRoundRoomAuthReturnTo(returnTo)) {
      window.location.replace(returnTo)
      return
    }
    void navigate(returnTo, { replace: true })
  }, [navigate, returnTo])

  useLayoutEffect(() => {
    const currentUrl = new URL(window.location.href)
    if (!currentUrl.searchParams.has('oauthError')) return
    currentUrl.searchParams.delete('oauthError')
    window.history.replaceState(
      window.history.state,
      '',
      `${currentUrl.pathname}${currentUrl.search}${currentUrl.hash}`,
    )
  }, [])

  useEffect(() => {
    if (safeRequestedReturnTo) {
      rememberAuthReturnTo(safeRequestedReturnTo)
    } else if (requestedReturnTo !== null) {
      clearRememberedAuthReturnTo()
    }
  }, [requestedReturnTo, safeRequestedReturnTo])

  useEffect(() => {
    if (!sessionQuery.data?.authenticated || returnTo === '/') return
    returnAfterAuthentication()
  }, [returnAfterAuthentication, returnTo, sessionQuery.data])

  const loginMutation = useMutation({
    mutationFn: () => createLocalSession(email, password),
    onSuccess: async () => {
      const session = await queryClient.fetchQuery(authSessionQueryOptions)
      if (!session.authenticated) {
        throw new Error('로그인 세션을 확인하지 못했습니다.')
      }
      setDeviceStateCleanupError('')
      returnAfterAuthentication()
    },
  })
  const logoutMutation = useMutation({
    mutationFn: deleteAuthSession,
    onSuccess: () => {
      const deviceStateCleared = clearAllWorkspaceDeviceState()
      queryClient.setQueryData(authSessionQueryKey, { authenticated: false })
      queryClient.removeQueries({ queryKey: accountMembershipKeys.all })
      queryClient.removeQueries({ queryKey: workspaceKeys.all })
      clearRememberedAuthReturnTo()
      setDeviceStateCleanupError(deviceStateCleared
        ? ''
        : '로그아웃했지만 이 기기의 작업 공간 접근 정보를 모두 지우지 못했습니다. 브라우저 저장을 허용한 뒤 이 기기 권한 제거를 다시 실행해 주세요.')
    },
  })

  if (sessionQuery.isPending) {
    return <div className="auth-loading" role="status">로그인 상태를 확인하고 있습니다.</div>
  }

  if (sessionQuery.data?.authenticated) {
    return (
      <div className="auth-session-card">
        <span className="auth-status-dot" aria-hidden="true" />
        <strong>이미 로그인되어 있습니다.</strong>
        <p>계정 ID {sessionQuery.data.accountId}</p>
        <div className="auth-session-actions">
          {isRoundRoomAuthReturnTo(returnTo)
            ? (
                <a className="primary-button auth-link-button" href={returnTo}>
                  ROUND 방으로 돌아가기
                </a>
              )
            : (
                <Link className="primary-button auth-link-button" to={returnTo}>
                  {returnTo === '/' ? '스터디로 이동' : '작업 공간으로 돌아가기'}
                </Link>
              )}
          <button
            className="text-button"
            type="button"
            disabled={logoutMutation.isPending}
            onClick={() => logoutMutation.mutate()}
          >
            {logoutMutation.isPending ? '로그아웃 중' : '로그아웃'}
          </button>
        </div>
        {logoutMutation.isError && (
          <p className="form-error" role="alert">{errorMessage(logoutMutation.error)}</p>
        )}
      </div>
    )
  }

  const providers = capabilitiesQuery.data?.providers ?? []

  return (
    <div className="auth-form-stack">
      {oauthCallbackError && (
        <p className="form-error" role="alert">
          <strong>{oauthCallbackError.title}</strong><br />
          {oauthCallbackError.detail}
        </p>
      )}

      {deviceStateCleanupError && (
        <div className="auth-capability-state auth-capability-state-error" role="alert">
          <strong>이 기기의 접근 정보 정리가 필요합니다.</strong>
          <p>{deviceStateCleanupError}</p>
        </div>
      )}

      {capabilitiesQuery.isPending && (
        <div className="auth-capability-state" role="status">
          <strong>소셜 로그인 수단을 확인하고 있습니다.</strong>
          <p>자체 이메일 로그인은 지금도 사용할 수 있습니다.</p>
        </div>
      )}

      {capabilitiesQuery.isError && (
        <div className="auth-capability-state auth-capability-state-error" role="alert">
          <strong>소셜 로그인 수단을 불러오지 못했습니다.</strong>
          <p>자체 이메일 로그인은 계속 사용할 수 있습니다.</p>
          <button
            className="auth-retry-button"
            type="button"
            disabled={capabilitiesQuery.isFetching}
            onClick={() => void capabilitiesQuery.refetch()}
          >
            {capabilitiesQuery.isFetching ? '다시 확인 중' : '소셜 로그인 다시 확인'}
          </button>
        </div>
      )}

      {capabilitiesQuery.isSuccess && providers.length > 0 && (
        <div className="social-login-list" aria-label="소셜 로그인">
          {providers.map((provider) => (
            <a
              className={`social-login social-login-${provider}`}
              href={`/oauth2/authorization/${provider}`}
              key={provider}
              onClick={() => {
                if (returnTo !== '/') rememberAuthReturnTo(returnTo)
              }}
            >
              <span aria-hidden="true">{provider === 'google' ? 'G' : 'N'}</span>
              {providerLabels[provider]}
            </a>
          ))}
        </div>
      )}

      {capabilitiesQuery.isSuccess && providers.length > 0 && (
        <div className="auth-divider"><span>또는 이메일</span></div>
      )}

      <form
        className="auth-form"
        onSubmit={(event) => {
          event.preventDefault()
          loginMutation.mutate()
        }}
      >
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
        <label>
          <span>비밀번호</span>
          <input
            autoComplete="current-password"
            maxLength={128}
            minLength={12}
            name="password"
            required
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        {loginMutation.isError && (
          <p className="form-error" role="alert">{errorMessage(loginMutation.error)}</p>
        )}
        {sessionQuery.isError && (
          <p className="form-error" role="alert">{errorMessage(sessionQuery.error)}</p>
        )}
        <button
          className="primary-button auth-submit"
          disabled={loginMutation.isPending}
          type="submit"
        >
          {loginMutation.isPending ? '로그인 중' : '이메일로 로그인'}
        </button>
      </form>

      {capabilitiesQuery.isSuccess
        && capabilitiesQuery.data.localRegistrationEnabled && (
          <p className="auth-switch-copy">
            자체 이메일 계정이 없나요? <Link to={returnTo === '/'
              ? '/register'
              : `/register?${new URLSearchParams({ returnTo })}`}>계정 만들기</Link>
          </p>
      )}
    </div>
  )
}
