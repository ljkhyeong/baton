import {
  decodeAuthProviders,
  decodeAuthSession,
  decodeCsrfToken,
  decodeLocalRegistration,
  decodeNoContent,
} from '@/features/auth/responseDecoder'
import type {
  AuthProviders,
  AuthSession,
  CsrfToken,
  LocalRegistrationRequest,
  LocalRegistrationResponse,
} from '@/features/auth/types'
import { apiRequest } from '@/shared/api/client'

const AUTH_ROOT = '/api/v1/auth'

export function getAuthProviders(): Promise<AuthProviders> {
  return apiRequest(`${AUTH_ROOT}/providers`, {
    method: 'GET',
    decode: decodeAuthProviders,
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
    decode: decodeNoContent,
  })
}

export async function createLocalSession(email: string, password: string): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/local/session`, {
    method: 'POST',
    body: new URLSearchParams({ email, password }),
    headers: await mutationHeaders(),
    decode: decodeNoContent,
  })
}

export async function deleteAuthSession(): Promise<void> {
  await apiRequest(`${AUTH_ROOT}/logout`, {
    method: 'POST',
    headers: await mutationHeaders(),
    decode: decodeNoContent,
  })
}
