export type AuthProvider = 'google' | 'naver'

export type AuthCapabilities = {
  providers: AuthProvider[]
  localRegistrationEnabled: boolean
}

export type AnonymousAuthSession = {
  authenticated: false
}

export type AuthenticatedAuthSession = {
  authenticated: true
  accountId: string
  csrfHeaderName: string
  csrfToken: string
}

export type AuthSession = AnonymousAuthSession | AuthenticatedAuthSession

export type CsrfToken = {
  csrfHeaderName: string
  csrfToken: string
}

export type LocalRegistrationRequest = {
  displayName: string
  email: string
}

export type LocalRegistrationResponse = {
  verificationRequired: true
}
