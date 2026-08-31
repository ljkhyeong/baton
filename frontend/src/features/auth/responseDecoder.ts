import type {
  AuthProvider,
  AuthCapabilities,
  AuthSession,
  CsrfToken,
  LocalRegistrationResponse,
  PasswordResetRequestResponse,
} from '@/features/auth/types'
import { isJsonObject, isNonEmptyString, isUuid } from '@/shared/api/responseValidation'

const AUTH_PROVIDERS = new Set<AuthProvider>(['google', 'naver'])

function isCsrfHeaderName(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    new Headers().set(value, 'csrf')
    return true
  } catch {
    return false
  }
}

export function decodeAuthCapabilities(value: unknown): AuthCapabilities {
  if (!isJsonObject(value)
    || !Array.isArray(value.providers)
    || typeof value.localRegistrationEnabled !== 'boolean'
    || typeof value.passwordResetEnabled !== 'boolean') {
    throw new Error('인증 capability 응답 형식이 올바르지 않습니다.')
  }

  const providers: AuthProvider[] = []
  for (const provider of value.providers) {
    if (typeof provider !== 'string'
      || !AUTH_PROVIDERS.has(provider as AuthProvider)) {
      throw new Error('인증 공급자 응답 값이 올바르지 않습니다.')
    }
    providers.push(provider as AuthProvider)
  }
  return {
    providers,
    localRegistrationEnabled: value.localRegistrationEnabled,
    passwordResetEnabled: value.passwordResetEnabled,
  }
}

export function decodeAuthSession(value: unknown): AuthSession {
  if (!isJsonObject(value) || typeof value.authenticated !== 'boolean') {
    throw new Error('인증 세션 응답 형식이 올바르지 않습니다.')
  }
  if (!value.authenticated) {
    return { authenticated: false }
  }
  if (!isUuid(value.accountId)
    || !isCsrfHeaderName(value.csrfHeaderName)
    || !isNonEmptyString(value.csrfToken)) {
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
    || !isCsrfHeaderName(value.csrfHeaderName)
    || !isNonEmptyString(value.csrfToken)) {
    throw new Error('CSRF 응답 형식이 올바르지 않습니다.')
  }
  return {
    csrfHeaderName: value.csrfHeaderName,
    csrfToken: value.csrfToken,
  }
}

export function decodeLocalRegistration(value: unknown): LocalRegistrationResponse {
  if (!isJsonObject(value)
    || typeof value.verificationRequired !== 'boolean') {
    throw new Error('가입 응답 형식이 올바르지 않습니다.')
  }
  return { verificationRequired: value.verificationRequired }
}

export function decodePasswordResetRequest(value: unknown): PasswordResetRequestResponse {
  if (!isJsonObject(value) || value.accepted !== true) {
    throw new Error('비밀번호 재설정 요청 결과를 확인하지 못했습니다.')
  }
  return { accepted: true }
}
