import type {
  AuthProvider,
  AuthCapabilities,
  AuthSession,
  CsrfToken,
  LocalRegistrationResponse,
  PasswordResetRequestResponse,
  AccountSecurity,
  AccountIdentityProvider,
} from '@/features/auth/types'
import { isJsonObject, isNonEmptyString, isUuid } from '@/shared/api/responseValidation'

const AUTH_PROVIDERS = new Set<AuthProvider>(['google', 'naver'])
const ACCOUNT_IDENTITY_PROVIDERS = new Set<AccountIdentityProvider>([
  'google',
  'naver',
  'local_email',
])

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
    || typeof value.passwordResetEnabled !== 'boolean'
    || (value.turnstileSiteKey !== null
      && (typeof value.turnstileSiteKey !== 'string' || !value.turnstileSiteKey))) {
    throw new Error('로그인 기능 응답 형식이 올바르지 않습니다.')
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
    turnstileSiteKey: value.turnstileSiteKey,
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

export function decodeAccountSecurity(value: unknown): AccountSecurity {
  if (!isJsonObject(value)
    || !isUuid(value.accountId)
    || !isNonEmptyString(value.displayName)
    || value.displayName.length > 100
    || !Array.isArray(value.identities)) {
    throw new Error('계정 보안 응답 형식이 올바르지 않습니다.')
  }

  const identities: AccountSecurity['identities'] = []
  for (const identity of value.identities) {
    if (!isJsonObject(identity)
      || typeof identity.provider !== 'string'
      || !ACCOUNT_IDENTITY_PROVIDERS.has(identity.provider as AccountIdentityProvider)
      || (identity.email !== null
        && (typeof identity.email !== 'string'
          || !identity.email
          || identity.email.length > 320))
      || typeof identity.emailVerified !== 'boolean') {
      throw new Error('계정 로그인 방법 응답 값이 올바르지 않습니다.')
    }
    identities.push({
      provider: identity.provider as AccountIdentityProvider,
      email: identity.email,
      emailVerified: identity.emailVerified,
    })
  }
  return {
    accountId: value.accountId,
    displayName: value.displayName,
    identities,
  }
}
