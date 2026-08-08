import type {
  AuthProvider,
  AuthProviders,
  AuthSession,
  CsrfToken,
  LocalRegistrationResponse,
} from '@/features/auth/types'
import { isJsonObject, isNonEmptyString, isUuid } from '@/shared/api/responseValidation'

const HTTP_TOKEN_PATTERN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/
const AUTH_PROVIDERS = new Set<AuthProvider>(['google', 'naver'])

function hasExactKeys(value: Record<string, unknown>, keys: string[]) {
  const actual = Object.keys(value).sort()
  const expected = [...keys].sort()
  return actual.length === expected.length
    && actual.every((key, index) => key === expected[index])
}

function isCsrfToken(value: unknown): value is string {
  return isNonEmptyString(value)
}

function isCsrfHeaderName(value: unknown): value is string {
  return typeof value === 'string' && HTTP_TOKEN_PATTERN.test(value)
}

export function decodeAuthProviders(value: unknown): AuthProviders {
  if (!isJsonObject(value)
    || !hasExactKeys(value, ['providers'])
    || !Array.isArray(value.providers)) {
    throw new Error('인증 공급자 응답 형식이 올바르지 않습니다.')
  }

  const providers: AuthProvider[] = []
  for (const provider of value.providers) {
    if (typeof provider !== 'string'
      || !AUTH_PROVIDERS.has(provider as AuthProvider)
      || providers.includes(provider as AuthProvider)) {
      throw new Error('인증 공급자 응답 값이 올바르지 않습니다.')
    }
    providers.push(provider as AuthProvider)
  }
  return { providers }
}

export function decodeAuthSession(value: unknown): AuthSession {
  if (!isJsonObject(value) || typeof value.authenticated !== 'boolean') {
    throw new Error('인증 세션 응답 형식이 올바르지 않습니다.')
  }
  if (!value.authenticated) {
    if (!hasExactKeys(value, ['authenticated'])) {
      throw new Error('익명 세션 응답 형식이 올바르지 않습니다.')
    }
    return { authenticated: false }
  }
  if (!hasExactKeys(value, [
    'authenticated',
    'accountId',
    'csrfHeaderName',
    'csrfToken',
  ])
    || !isUuid(value.accountId)
    || !isCsrfHeaderName(value.csrfHeaderName)
    || !isCsrfToken(value.csrfToken)) {
    throw new Error('인증 세션 응답 값이 올바르지 않습니다.')
  }
  return {
    authenticated: true,
    accountId: value.accountId,
    csrfHeaderName: value.csrfHeaderName,
    csrfToken: value.csrfToken,
  }
}

export function decodeCsrfToken(value: unknown): CsrfToken {
  if (!isJsonObject(value)
    || !hasExactKeys(value, ['csrfHeaderName', 'csrfToken'])
    || !isCsrfHeaderName(value.csrfHeaderName)
    || !isCsrfToken(value.csrfToken)) {
    throw new Error('CSRF 응답 형식이 올바르지 않습니다.')
  }
  return {
    csrfHeaderName: value.csrfHeaderName,
    csrfToken: value.csrfToken,
  }
}

export function decodeLocalRegistration(value: unknown): LocalRegistrationResponse {
  if (!isJsonObject(value)
    || !hasExactKeys(value, ['verificationRequired'])
    || value.verificationRequired !== true) {
    throw new Error('가입 응답 형식이 올바르지 않습니다.')
  }
  return { verificationRequired: true }
}

export function decodeNoContent(value: unknown): undefined {
  if (value !== undefined) {
    throw new Error('빈 응답이어야 합니다.')
  }
  return undefined
}
