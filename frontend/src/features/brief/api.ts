import { getCsrfToken } from '@/features/auth/api'
import { apiRequest } from '@/shared/api/client'
import { ApiError } from '@/shared/api/ApiError'
import { decodeBriefEdition, decodeBriefGeneration } from './responseDecoder'
import type { BriefScope } from './types'

function editionsPath(scope: BriefScope) {
  return `/api/v1/teams/${encodeURIComponent(scope.teamId)}/seasons/${encodeURIComponent(scope.seasonId)}/brief/editions`
}

export async function getLatestBriefEdition(scope: BriefScope, signal: AbortSignal) {
  try {
    return await apiRequest(`${editionsPath(scope)}/latest`, {
      method: 'GET',
      headers: { 'X-Baton-Access-Key': scope.accessKey },
      signal,
      decode: (value) => decodeBriefEdition(value, scope),
    })
  } catch (error) {
    if (error instanceof ApiError && error.status === 404 && error.code === 'BRIEF_EDITION_NOT_FOUND') return null
    throw error
  }
}

export async function generateBriefEdition(scope: BriefScope) {
  const csrf = await getCsrfToken()
  return apiRequest(editionsPath(scope), {
    method: 'POST',
    headers: {
      'X-Baton-Access-Key': scope.accessKey,
      [csrf.csrfHeaderName]: csrf.csrfToken,
    },
    decode: decodeBriefGeneration,
  })
}
