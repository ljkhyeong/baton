import { apiRequest } from '@/shared/api/client'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoutineRequest,
  CreateWorkspaceRequest,
  CreateWorkspaceResponse,
  RotateAccessKeyResponse,
  WorkspaceProjection,
} from './types'

export type WorkspaceScope = {
  teamId: string
  seasonId: string
  accessKey: string
}

export const accessKeyStorageKey = (teamId: string) => `baton-access-key:${teamId}`

export function saveAccessKey(teamId: string, accessKey: string) {
  try {
    window.localStorage.setItem(accessKeyStorageKey(teamId), accessKey)
    return true
  } catch {
    return false
  }
}

export function readAccessKey(teamId: string) {
  try {
    return window.localStorage.getItem(accessKeyStorageKey(teamId)) ?? ''
  } catch {
    return ''
  }
}

export type CreateWorkspaceOptions = {
  idempotencyKey: string
  creationKey?: string
}

export function createWorkspace(request: CreateWorkspaceRequest, options: CreateWorkspaceOptions) {
  return apiRequest<CreateWorkspaceResponse>('/api/v1/workspaces', {
    method: 'POST',
    headers: {
      'Idempotency-Key': options.idempotencyKey,
      ...(options.creationKey ? { 'X-Baton-Creation-Key': options.creationKey } : {}),
    },
    body: request,
  })
}

function scopedPath({ teamId, seasonId }: WorkspaceScope, suffix: string) {
  return `/api/v1/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}${suffix}`
}

function scopedHeaders(scope: WorkspaceScope) {
  return { 'X-Baton-Access-Key': scope.accessKey }
}

export function getWorkspace(scope: WorkspaceScope) {
  return apiRequest<WorkspaceProjection>(scopedPath(scope, '/workspace'), {
    headers: scopedHeaders(scope),
  })
}

export function rotateAccessKey(scope: WorkspaceScope, idempotencyKey: string) {
  return apiRequest<RotateAccessKeyResponse>(scopedPath(scope, '/access-key/rotate'), {
    method: 'POST',
    headers: {
      ...scopedHeaders(scope),
      'Idempotency-Key': idempotencyKey,
    },
  })
}

export function createRole(scope: WorkspaceScope, request: CreateRoleRequest) {
  return apiRequest<unknown>(scopedPath(scope, '/roles'), {
    method: 'POST',
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function createRoutine(scope: WorkspaceScope, request: CreateRoutineRequest) {
  return apiRequest<unknown>(scopedPath(scope, '/routines'), {
    method: 'POST',
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function setRoutineCompletion(scope: WorkspaceScope, routineId: string, completed: boolean) {
  return apiRequest<unknown>(scopedPath(scope, `/routines/${encodeURIComponent(routineId)}/completion`), {
    method: 'PATCH',
    headers: scopedHeaders(scope),
    body: { completed },
  })
}

export function createDecision(scope: WorkspaceScope, request: CreateDecisionRequest) {
  return apiRequest<unknown>(scopedPath(scope, '/decisions'), {
    method: 'POST',
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function createHandoffItem(scope: WorkspaceScope, request: CreateHandoffItemRequest) {
  return apiRequest<unknown>(scopedPath(scope, '/handoff-items'), {
    method: 'POST',
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function setHandoffItemCompletion(scope: WorkspaceScope, itemId: string, completed: boolean) {
  return apiRequest<unknown>(scopedPath(scope, `/handoff-items/${encodeURIComponent(itemId)}/completion`), {
    method: 'PATCH',
    headers: scopedHeaders(scope),
    body: { completed },
  })
}
