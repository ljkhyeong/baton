import { apiRequest } from '@/shared/api/client'
import { resolveEndpointPath, workspaceEndpoints } from './contract'
import type {
  CreateDecisionHeaders,
  CreateDecisionRequest,
  CreateHandoffItemHeaders,
  CreateHandoffItemRequest,
  CreateRoleHeaders,
  CreateRoleResourceHeaders,
  CreateRoleResourceRequest,
  CreateRoleRequest,
  CreateRoutineHeaders,
  CreateRoutineRequest,
  CreateSeasonRoundHeaders,
  CreateSeasonRoundRequest,
  CreateWorkspaceHeaders,
  CreateWorkspaceRequest,
  CreateWorkspaceResponse,
  Decision,
  HandoffItem,
  Role,
  RoleResource,
  RotateAccessKeyResponse,
  RotateAccessKeyHeaders,
  Routine,
  RoutineExecution,
  SeasonRound,
  UpdateRoleHeaders,
  UpdateRoleResourceHeaders,
  UpdateRoleResourceRequest,
  UpdateRoleResourceResponse,
  UpdateRoleRequest,
  UpdateRoleResponse,
  UpdateDecisionArchiveHeaders,
  UpdateDecisionArchiveResponse,
  UpdateDecisionHeaders,
  UpdateDecisionRequest,
  UpdateDecisionResponse,
  UpdateHandoffItemArchiveHeaders,
  UpdateHandoffItemArchiveResponse,
  UpdateHandoffItemHeaders,
  UpdateHandoffItemRequest,
  UpdateHandoffItemResponse,
  UpdateHandoffItemCompletionRequest,
  UpdateHandoffItemCompletionResponse,
  UpdateHandoffItemCompletionHeaders,
  UpdateRecordArchiveRequest,
  UpdateRoutineHeaders,
  UpdateRoutineRequest,
  UpdateRoutineResponse,
  UpdateRoutineExecutionCompletionHeaders,
  UpdateRoutineExecutionCompletionRequest,
  UpdateSeasonRoundArchiveHeaders,
  UpdateSeasonRoundArchiveRequest,
  UpdateSeasonRoundArchiveResponse,
  UpdateSeasonRoundHeaders,
  UpdateSeasonRoundRequest,
  UpdateSeasonRoundResponse,
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

export function updateRole(scope: WorkspaceScope, roleId: string, request: UpdateRoleRequest) {
  const endpoint = workspaceEndpoints.updateRole
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roleId,
  })
  return apiRequest<UpdateRoleResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoleHeaders,
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

export function updateRoutine(
  scope: WorkspaceScope,
  routineId: string,
  request: UpdateRoutineRequest,
) {
  const endpoint = workspaceEndpoints.updateRoutine
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    routineId,
  })
  return apiRequest<UpdateRoutineResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoutineHeaders,
    body: request,
  })
}

export function createSeasonRound(
  scope: WorkspaceScope,
  request: CreateSeasonRoundRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createSeasonRound
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<SeasonRound>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateSeasonRoundHeaders,
    body: request,
  })
}

export function updateSeasonRound(
  scope: WorkspaceScope,
  roundId: string,
  request: UpdateSeasonRoundRequest,
) {
  const endpoint = workspaceEndpoints.updateSeasonRound
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roundId,
  })
  return apiRequest<UpdateSeasonRoundResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateSeasonRoundHeaders,
    body: request,
  })
}

export function setSeasonRoundArchived(
  scope: WorkspaceScope,
  roundId: string,
  archived: boolean,
) {
  const endpoint = workspaceEndpoints.updateSeasonRoundArchive
  const body: UpdateSeasonRoundArchiveRequest = { archived }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roundId,
  })
  return apiRequest<UpdateSeasonRoundArchiveResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateSeasonRoundArchiveHeaders,
    body,
  })
}

export function setRoutineExecutionCompletion(
  scope: WorkspaceScope,
  roundId: string,
  executionId: string,
  completed: boolean,
) {
  const endpoint = workspaceEndpoints.updateRoutineExecutionCompletion
  const body: UpdateRoutineExecutionCompletionRequest = { completed }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roundId,
    executionId,
  })
  return apiRequest<RoutineExecution>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoutineExecutionCompletionHeaders,
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

export function updateDecision(
  scope: WorkspaceScope,
  decisionId: string,
  request: UpdateDecisionRequest,
) {
  const endpoint = workspaceEndpoints.updateDecision
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    decisionId,
  })
  return apiRequest<UpdateDecisionResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateDecisionHeaders,
    body: request,
  })
}

export function setDecisionArchived(
  scope: WorkspaceScope,
  decisionId: string,
  archived: boolean,
) {
  const endpoint = workspaceEndpoints.updateDecisionArchive
  const body: UpdateRecordArchiveRequest = { archived }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    decisionId,
  })
  return apiRequest<UpdateDecisionArchiveResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateDecisionArchiveHeaders,
    body,
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

export function updateHandoffItem(
  scope: WorkspaceScope,
  itemId: string,
  request: UpdateHandoffItemRequest,
) {
  const endpoint = workspaceEndpoints.updateHandoffItem
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    itemId,
  })
  return apiRequest<UpdateHandoffItemResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateHandoffItemHeaders,
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

export function setHandoffItemArchived(
  scope: WorkspaceScope,
  itemId: string,
  archived: boolean,
) {
  const endpoint = workspaceEndpoints.updateHandoffItemArchive
  const body: UpdateRecordArchiveRequest = { archived }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    itemId,
  })
  return apiRequest<UpdateHandoffItemArchiveResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateHandoffItemArchiveHeaders,
    body,
  })
}

export function createRoleResource(
  scope: WorkspaceScope,
  request: CreateRoleResourceRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createRoleResource
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<RoleResource>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateRoleResourceHeaders,
    body: request,
  })
}

export function updateRoleResource(
  scope: WorkspaceScope,
  resourceId: string,
  request: UpdateRoleResourceRequest,
) {
  const endpoint = workspaceEndpoints.updateRoleResource
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    resourceId,
  })
  return apiRequest<UpdateRoleResourceResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoleResourceHeaders,
    body: request,
  })
}
