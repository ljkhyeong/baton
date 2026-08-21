import { isJsonObject } from '@/shared/api/responseValidation'
import {
  clearMatchingJsonItem,
  readValidatedJson,
  writeJson,
} from '@/shared/lib/durableStorage'
import { runWithBrowserLock } from '@/shared/lib/browserLock'
import type { BrowserLockResult } from '@/shared/lib/browserLock'
import { isValidIdempotencyKey } from '@/shared/lib/idempotencyKey'
import type { CreateNextSeasonRequest } from './types'

const STORAGE_KEY_PREFIX = 'baton-pending-season-successor:v1:'
const LOCK_NAME_PREFIX = 'baton-season-successor:'

type PendingSeasonSuccessor = {
  teamId: string
  sourceSeasonId: string
  normalizedPayload: string
  idempotencyKey: string
}

type SeasonSuccessorPreparation =
  | { status: 'ready'; idempotencyKey: string }
  | { status: 'blocked'; reason: 'storageUnavailable' | 'differentRequestPending' }

export type SeasonSuccessorCleanupRetry = {
  teamId: string
  sourceSeasonId: string
  request: CreateNextSeasonRequest
  idempotencyKey: string
}

function storageKey(teamId: string) {
  return `${STORAGE_KEY_PREFIX}${teamId}`
}

function lockName(teamId: string) {
  return `${LOCK_NAME_PREFIX}${teamId}`
}

function normalizedRequest(request: CreateNextSeasonRequest) {
  return JSON.stringify({
    name: request.name.trim(),
    startDate: request.startDate.trim(),
    endDate: request.endDate.trim(),
    copyRoleIds: [...request.copyRoleIds].map((id) => id.trim()).sort(),
    copyRoutineIds: [...request.copyRoutineIds].map((id) => id.trim()).sort(),
  })
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isNormalizedPayload(value: unknown): value is string {
  if (typeof value !== 'string') return false
  try {
    const request = JSON.parse(value) as Partial<CreateNextSeasonRequest>
    return typeof request.name === 'string'
      && typeof request.startDate === 'string'
      && typeof request.endDate === 'string'
      && isStringArray(request.copyRoleIds)
      && isStringArray(request.copyRoutineIds)
      && normalizedRequest(request as CreateNextSeasonRequest) === value
  } catch {
    return false
  }
}

function isPendingSeasonSuccessor(value: unknown): value is PendingSeasonSuccessor {
  if (!isJsonObject(value)) return false
  const candidate = value as Partial<PendingSeasonSuccessor>
  return typeof candidate.teamId === 'string'
    && candidate.teamId.length > 0
    && typeof candidate.sourceSeasonId === 'string'
    && candidate.sourceSeasonId.length > 0
    && isNormalizedPayload(candidate.normalizedPayload)
    && isValidIdempotencyKey(candidate.idempotencyKey)
}

function readPending(teamId: string) {
  return readValidatedJson(storageKey(teamId), isPendingSeasonSuccessor)
}

export function confirmedSeasonSuccessorCleanupRetry(
  teamId: string,
  confirmedSourceSeasonId: string | null,
): SeasonSuccessorCleanupRetry | null {
  if (!confirmedSourceSeasonId) return null
  const pending = readPending(teamId)
  if (!pending
    || pending.teamId !== teamId
    || pending.sourceSeasonId !== confirmedSourceSeasonId) return null

  return {
    teamId,
    sourceSeasonId: pending.sourceSeasonId,
    request: JSON.parse(pending.normalizedPayload) as CreateNextSeasonRequest,
    idempotencyKey: pending.idempotencyKey,
  }
}

export function prepareSeasonSuccessor(
  teamId: string,
  sourceSeasonId: string,
  request: CreateNextSeasonRequest,
): SeasonSuccessorPreparation {
  const payload = normalizedRequest(request)
  const existing = readPending(teamId)
  if (existing) {
    return existing.teamId === teamId
      && existing.sourceSeasonId === sourceSeasonId
      && existing.normalizedPayload === payload
      ? { status: 'ready', idempotencyKey: existing.idempotencyKey }
      : { status: 'blocked', reason: 'differentRequestPending' }
  }

  const pending: PendingSeasonSuccessor = {
    teamId,
    sourceSeasonId,
    normalizedPayload: payload,
    idempotencyKey: crypto.randomUUID(),
  }
  return writeJson(storageKey(teamId), pending)
    ? { status: 'ready', idempotencyKey: pending.idempotencyKey }
    : { status: 'blocked', reason: 'storageUnavailable' }
}

export function clearPendingSeasonSuccessor(
  teamId: string,
  sourceSeasonId: string,
  request: CreateNextSeasonRequest,
  idempotencyKey: string,
) {
  const payload = normalizedRequest(request)
  return clearMatchingJsonItem(
    storageKey(teamId),
    isPendingSeasonSuccessor,
    (pending) => pending.teamId === teamId
      && pending.sourceSeasonId === sourceSeasonId
      && pending.normalizedPayload === payload
      && pending.idempotencyKey === idempotencyKey,
  )
}

export async function runWithSeasonSuccessorLock<Value>(
  teamId: string,
  operation: () => Promise<Value>,
): Promise<BrowserLockResult<Value>> {
  return runWithBrowserLock(lockName(teamId), operation)
}
