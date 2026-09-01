import {
  decodeAuthCapabilities,
  decodeAuthSession,
  decodeCsrfToken,
  decodeLocalRegistration,
  decodePasswordResetRequest,
  decodeAccountSecurity,
} from '@/features/auth/responseDecoder'
import type {
  AuthCapabilities,
  AuthSession,
  CsrfToken,
  LocalRegistrationRequest,
  LocalRegistrationResponse,
  AccountSecurity,
  LocalPasswordChangeRequest,
} from '@/features/auth/types'
import { apiRequest } from '@/shared/api/client'

const AUTH_ROOT = '/api/v1/auth'

export function getAuthCapabilities(): Promise<AuthCapabilities> {
  return apiRequest(`${AUTH_ROOT}/providers`, {
    method: 'GET',
    decode: decodeAuthCapabilities,
  })
}

export function getAuthSession(): Promise<AuthSession> {
  return apiRequest(`${AUTH_ROOT}/session`, {
    method: 'GET',
    decode: decodeAuthSession,
  })
}

export function getCsrfToken(): Promise<CsrfToken> {
  return apiRequest(`${AUTH_ROOT}/csrf`, {
    method: 'GET',
    decode: decodeCsrfToken,
  })
}

async function mutationHeaders() {
  const token = await getCsrfToken()
  return { [token.csrfHeaderName]: token.csrfToken }
}

export async function registerLocalAccount(
  request: LocalRegistrationRequest,
): Promise<LocalRegistrationResponse> {
  return apiRequest(`${AUTH_ROOT}/local/registrations`, {
    method: 'POST',
    body: request,
    headers: await mutationHeaders(),
    decode: decodeLocalRegistration,
  })
}

export async function verifyLocalEmail(token: string, password: string): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/local/email-verifications`, {
    method: 'POST',
    body: { token, password },
    headers: await mutationHeaders(),
    responseType: 'no-content',
  })
}

export async function createLocalSession(email: string, password: string): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/local/session`, {
    method: 'POST',
    body: new URLSearchParams({ email, password }),
    headers: await mutationHeaders(),
    responseType: 'no-content',
  })
}

export async function deleteAuthSession(): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/logout`, {
    method: 'POST',
    headers: await mutationHeaders(),
    responseType: 'no-content',
  })
}

export async function requestPasswordReset(email: string): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/local/password-reset-requests`, {
    method: 'POST',
    body: { email },
    headers: await mutationHeaders(),
    decode: decodePasswordResetRequest,
  })
}

export async function resetPassword(token: string, password: string): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/local/password-resets`, {
    method: 'POST',
    body: { token, password },
    headers: await mutationHeaders(),
    responseType: 'no-content',
  })
}

export function getAccountSecurity(): Promise<AccountSecurity> {
  return apiRequest(`${AUTH_ROOT}/account`, {
    method: 'GET',
    decode: decodeAccountSecurity,
  })
}

export async function changeLocalPassword(
  request: LocalPasswordChangeRequest,
): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/local/password-changes`, {
    method: 'POST',
    body: request,
    headers: await mutationHeaders(),
    responseType: 'no-content',
  })
}

export async function revokeAccountSessions(): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/session-revocations`, {
    method: 'POST',
    headers: await mutationHeaders(),
    responseType: 'no-content',
  })
}
