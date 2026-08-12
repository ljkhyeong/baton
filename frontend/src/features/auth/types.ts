import type { operations } from '@/generated/api'

type AuthCapabilitiesOperation = operations['getAuthProviders']
type AuthSessionOperation = operations['getAuthSession']
type CsrfTokenOperation = operations['getAuthCsrf']
type LocalRegistrationOperation = operations['registerLocalAccount']

export type AuthCapabilities =
  AuthCapabilitiesOperation['responses'][200]['content']['application/json']

export type AuthProvider = AuthCapabilities['providers'][number]

export type AuthSession =
  AuthSessionOperation['responses'][200]['content']['application/json']

export type AnonymousAuthSession = Extract<AuthSession, { authenticated: false }>

export type AuthenticatedAuthSession = Extract<AuthSession, { authenticated: true }>

export type CsrfToken =
  CsrfTokenOperation['responses'][200]['content']['application/json']

export type LocalRegistrationRequest =
  LocalRegistrationOperation['requestBody']['content']['application/json']

export type LocalRegistrationResponse =
  LocalRegistrationOperation['responses'][202]['content']['application/json']
