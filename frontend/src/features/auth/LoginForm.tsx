import { useMutation, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import {
  createLocalSession,
  deleteAuthSession,
  getAuthProviders,
  getAuthSession,
} from '@/features/auth/api'
import { authSessionQueryKey, useAuthSession } from '@/features/auth/useAuthSession'
import { queryClient } from '@/shared/api/queryClient'

const providerLabels = {
  google: 'Google로 계속하기',
  naver: 'Naver로 계속하기',
} as const

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '로그인 요청을 처리하지 못했습니다.'
}

export default function LoginForm() {
  const navigate = useNavigate()
  const sessionQuery = useAuthSession()
  const providersQuery = useQuery({
    queryKey: ['auth', 'providers'],
    queryFn: getAuthProviders,
    retry: false,
  })
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const loginMutation = useMutation({
    mutationFn: () => createLocalSession(email, password),
    onSuccess: async () => {
      const session = await queryClient.fetchQuery({
        queryKey: authSessionQueryKey,
        queryFn: getAuthSession,
        staleTime: 0,
      })
      if (!session.authenticated) {
        throw new Error('로그인 세션을 확인하지 못했습니다.')
      }
      navigate('/', { replace: true })
    },
  })
  const logoutMutation = useMutation({
    mutationFn: deleteAuthSession,
    onSuccess: () => {
      queryClient.setQueryData(authSessionQueryKey, { authenticated: false })
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
          <Link className="primary-button auth-link-button" to="/">스터디로 이동</Link>
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

  const providers = providersQuery.data?.providers ?? []

  return (
    <div className="auth-form-stack">
      {providers.length > 0 && (
        <div className="social-login-list" aria-label="소셜 로그인">
          {providers.map((provider) => (
            <a
              className={`social-login social-login-${provider}`}
              href={`/oauth2/authorization/${provider}`}
              key={provider}
            >
              <span aria-hidden="true">{provider === 'google' ? 'G' : 'N'}</span>
              {providerLabels[provider]}
            </a>
          ))}
        </div>
      )}

      {providers.length > 0 && <div className="auth-divider"><span>또는 이메일</span></div>}

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

      <p className="auth-switch-copy">
        자체 이메일 계정이 없나요? <Link to="/register">계정 만들기</Link>
      </p>
    </div>
  )
}
