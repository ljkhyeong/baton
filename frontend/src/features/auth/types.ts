import type { operations } from '@/generated/api'

type AuthCapabilitiesOperation = operations['getAuthProviders']
type AuthSessionOperation = operations['getAuthSession']
type CsrfTokenOperation = operations['getAuthCsrf']
type LocalRegistrationOperation = operations['registerLocalAccount']
type AccountSecurityOperation = operations['getAccountSecurity']
type LocalPasswordChangeOperation = operations['changeLocalPassword']

export type AuthCapabilities =
  AuthCapabilitiesOperation['responses'][200]['content']['application/json']

export type AuthProvider = AuthCapabilities['providers'][number]

export type AuthSession =
  AuthSessionOperation['responses'][200]['content']['application/json']

export type CsrfToken =
  CsrfTokenOperation['responses'][200]['content']['application/json']

export type LocalRegistrationRequest =
  LocalRegistrationOperation['requestBody']['content']['application/json']

export type LocalRegistrationResponse =
  LocalRegistrationOperation['responses'][202]['content']['application/json']

export type PasswordResetRequestResponse =
  operations['requestPasswordReset']['responses'][202]['content']['application/json']

type GeneratedAccountSecurity =
  AccountSecurityOperation['responses'][200]['content']['application/json']

export type AccountIdentityProvider = 'google' | 'naver' | 'local_email'

export type AccountSecurity = Omit<GeneratedAccountSecurity, 'identities'> & {
  identities: Array<
    Omit<GeneratedAccountSecurity['identities'][number], 'provider'>
    & { provider: AccountIdentityProvider }
  >
}

export type LocalPasswordChangeRequest =
  LocalPasswordChangeOperation['requestBody']['content']['application/json']
