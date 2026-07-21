import { apiRequest } from '@/shared/api/client'
import { resolveEndpointPath, workspaceEndpoints } from './contract'
import type {
  CreateDecisionHeaders,
  CreateDecisionRequest,
  CreateHandoffItemHeaders,
  CreateHandoffItemRequest,
  CreateRoleHeaders,
  CreateRoleRequest,
  CreateRoutineHeaders,
  CreateRoutineRequest,
  CreateWorkspaceHeaders,
  CreateWorkspaceRequest,
  CreateWorkspaceResponse,
  Decision,
  HandoffItem,
  Role,
  RotateAccessKeyResponse,
  RotateAccessKeyHeaders,
  Routine,
  UpdateHandoffItemCompletionRequest,
  UpdateHandoffItemCompletionResponse,
  UpdateHandoffItemCompletionHeaders,
  UpdateRoutineCompletionHeaders,
  UpdateRoutineCompletionRequest,
  UpdateRoutineCompletionResponse,
  WorkspaceAccessHeaders,
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
  const endpoint = workspaceEndpoints.createWorkspace
  return apiRequest<CreateWorkspaceResponse>(endpoint.path, {
    method: endpoint.method,
    headers: {
      'Idempotency-Key': options.idempotencyKey,
      ...(options.creationKey ? { 'X-Baton-Creation-Key': options.creationKey } : {}),
    } satisfies CreateWorkspaceHeaders,
    body: request,
  })
}

function scopedHeaders(scope: WorkspaceScope): WorkspaceAccessHeaders {
  return { 'X-Baton-Access-Key': scope.accessKey }
}

function scopedParameters({ teamId, seasonId }: WorkspaceScope) {
  return { teamId, seasonId }
}

function contentCreationHeaders(scope: WorkspaceScope, idempotencyKey: string) {
  return {
    ...scopedHeaders(scope),
    'Idempotency-Key': idempotencyKey,
  }
}

export function getWorkspace(scope: WorkspaceScope) {
  const endpoint = workspaceEndpoints.getWorkspace
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<WorkspaceProjection>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
  })
}

export function rotateAccessKey(scope: WorkspaceScope, idempotencyKey: string) {
  const endpoint = workspaceEndpoints.rotateAccessKey
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<RotateAccessKeyResponse>(path, {
    method: endpoint.method,
    headers: {
      ...scopedHeaders(scope),
      'Idempotency-Key': idempotencyKey,
    } satisfies RotateAccessKeyHeaders,
  })
}

export function createRole(
  scope: WorkspaceScope,
  request: CreateRoleRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createRole
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<Role>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateRoleHeaders,
    body: request,
  })
}

export function createRoutine(
  scope: WorkspaceScope,
  request: CreateRoutineRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createRoutine
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<Routine>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateRoutineHeaders,
    body: request,
  })
}

export function setRoutineCompletion(scope: WorkspaceScope, routineId: string, completed: boolean) {
  const endpoint = workspaceEndpoints.updateRoutineCompletion
  const body: UpdateRoutineCompletionRequest = { completed }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    routineId,
  })
  return apiRequest<UpdateRoutineCompletionResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoutineCompletionHeaders,
    body,
  })
}

export function createDecision(
  scope: WorkspaceScope,
  request: CreateDecisionRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createDecision
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<Decision>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateDecisionHeaders,
    body: request,
  })
}

export function createHandoffItem(
  scope: WorkspaceScope,
  request: CreateHandoffItemRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createHandoffItem
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<HandoffItem>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateHandoffItemHeaders,
    body: request,
  })
}

export function setHandoffItemCompletion(scope: WorkspaceScope, itemId: string, completed: boolean) {
  const endpoint = workspaceEndpoints.updateHandoffItemCompletion
  const body: UpdateHandoffItemCompletionRequest = { completed }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    itemId,
  })
  return apiRequest<UpdateHandoffItemCompletionResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateHandoffItemCompletionHeaders,
    body,
  })
}
