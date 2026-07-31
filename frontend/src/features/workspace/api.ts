import { apiRequest } from '@/shared/api/client'
import type { RequestHeaderProvider } from '@/shared/api/client'
import type { CsrfCredential } from '@/features/identity/types'
import {
  resolveEndpointPath,
  seasonLifecycleEndpoints,
  workspaceEndpoints,
} from './contract'
import type {
  AcceptRoleHandoffResponse,
  CancelRoleHandoffRequest,
  CancelRoleHandoffResponse,
  ConfirmRoleHandoffRequest,
  CreateNextSeasonRequest,
  CreateNextSeasonResponse,
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateMemberRequest,
  CreateOwnedWorkspaceRequest,
  CreateOwnedWorkspaceResponse,
  CreateRoleResourceRequest,
  CreateRoleRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  CreateWorkspaceHeaders,
  CreateWorkspaceRequest,
  CreateWorkspaceResponse,
  Decision,
  HandoffItem,
  Member,
  OpenRoleResourceLinkRequest,
  OpenRoleResourceLinkResponse,
  PrepareRoleHandoffRequest,
  PrepareRoleHandoffResponse,
  Role,
  RoleResource,
  RotateAccessKeyResponse,
  Routine,
  RoutineExecution,
  SeasonRound,
  SeasonSummary,
  UpdateRoleResourceRequest,
  UpdateRoleResourceResponse,
  UpdateRoleRequest,
  UpdateRoleResponse,
  UpdateDecisionArchiveResponse,
  UpdateDecisionRequest,
  UpdateDecisionResponse,
  UpdateHandoffItemArchiveResponse,
  UpdateHandoffItemRequest,
  UpdateHandoffItemResponse,
  UpdateHandoffItemCompletionRequest,
  UpdateHandoffItemCompletionResponse,
  UpdateRecordArchiveRequest,
  UpdateMemberDeactivationRequest,
  UpdateMemberDeactivationResponse,
  UpdateMemberRequest,
  UpdateMemberResponse,
  UpdateRoutineRequest,
  UpdateRoutineResponse,
  UpdateRoutineExecutionCompletionRequest,
  UpdateRoundScheduleRequest,
  UpdateSeasonEndingRequest,
  UpdateSeasonRequest,
  UpdateSeasonRoundArchiveRequest,
  UpdateSeasonRoundArchiveResponse,
  UpdateSeasonRoundRequest,
  UpdateSeasonRoundResponse,
  TransferRoleHandoffRequest,
  TransferRoleHandoffResponse,
  WorkspaceProjection,
} from './types'

export type WorkspaceScope = {
  teamId: string
  seasonId: string
  authCacheIdentity: string
  authorizationHeaders: RequestHeaderProvider
}

export type WorkspaceAccess =
  | {
      mode: 'session'
      accountId: string
    }
  | {
      mode: 'legacy'
      accessKey: string
      cacheIdentity: string
    }

type CurrentCsrfCredential = () => Promise<CsrfCredential | null>

function csrfHeaders(credential: CsrfCredential | null) {
  return credential
    ? { [credential.headerName]: credential.token }
    : {}
}

function unsafeMethod(method: string) {
  return method !== 'GET' && method !== 'HEAD' && method !== 'OPTIONS'
}

export function createWorkspaceScope(
  teamId: string,
  seasonId: string,
  access: WorkspaceAccess,
  currentCsrfCredential: CurrentCsrfCredential,
): WorkspaceScope {
  const authCacheIdentity = access.mode === 'session'
    ? `account:${access.accountId}`
    : `legacy:${access.cacheIdentity}`

  return {
    teamId,
    seasonId,
    authCacheIdentity,
    authorizationHeaders: async ({ method }) => {
      const accessHeaders: Record<string, string> = access.mode === 'legacy'
        ? { 'X-Baton-Access-Key': access.accessKey }
        : {}
      if (!unsafeMethod(method)) return accessHeaders

      const credential = await currentCsrfCredential()
      if (access.mode === 'session' && !credential) {
        throw new Error('로그인 세션을 다시 확인해 주세요.')
      }
      return {
        ...accessHeaders,
        ...csrfHeaders(credential),
      }
    },
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

export type CreateOwnedWorkspaceOptions = {
  idempotencyKey: string
  csrfCredential: CsrfCredential
}

export function createOwnedWorkspace(
  request: CreateOwnedWorkspaceRequest,
  options: CreateOwnedWorkspaceOptions,
) {
  const endpoint = workspaceEndpoints.createOwnedWorkspace
  return apiRequest<CreateOwnedWorkspaceResponse>(endpoint.path, {
    method: endpoint.method,
    headers: {
      'Idempotency-Key': options.idempotencyKey,
      [options.csrfCredential.headerName]: options.csrfCredential.token,
    },
    body: request,
  })
}

function scopedHeaders(
  scope: WorkspaceScope,
  additional: HeadersInit = {},
): RequestHeaderProvider {
  return async (request) => ({
    ...await scope.authorizationHeaders(request),
    ...additional,
  })
}

function scopedParameters({ teamId, seasonId }: WorkspaceScope) {
  return { teamId, seasonId }
}

function contentCreationHeaders(scope: WorkspaceScope, idempotencyKey: string) {
  return scopedHeaders(scope, {
    'Idempotency-Key': idempotencyKey,
  })
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
    headers: scopedHeaders(scope, {
      'Idempotency-Key': idempotencyKey,
    }),
  })
}

export function updateSeason(scope: WorkspaceScope, request: UpdateSeasonRequest) {
  const endpoint = seasonLifecycleEndpoints.updateSeason
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<SeasonSummary>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function updateRoundSchedule(
  scope: WorkspaceScope,
  request: UpdateRoundScheduleRequest,
) {
  const endpoint = seasonLifecycleEndpoints.updateRoundSchedule
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<SeasonSummary>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function updateSeasonEnding(
  scope: WorkspaceScope,
  request: UpdateSeasonEndingRequest,
) {
  const endpoint = seasonLifecycleEndpoints.updateSeasonEnding
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<SeasonSummary>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function createNextSeason(
  scope: WorkspaceScope,
  request: CreateNextSeasonRequest,
  idempotencyKey: string,
) {
  const endpoint = seasonLifecycleEndpoints.createNextSeason
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<CreateNextSeasonResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope, {
      'Idempotency-Key': idempotencyKey,
    }),
    body: request,
  })
}

export function createMember(
  scope: WorkspaceScope,
  request: CreateMemberRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.createMember
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<Member>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey),
    body: request,
  })
}

export function updateMember(
  scope: WorkspaceScope,
  memberId: string,
  request: UpdateMemberRequest,
) {
  const endpoint = workspaceEndpoints.updateMember
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    memberId,
  })
  return apiRequest<UpdateMemberResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function updateMemberDeactivation(
  scope: WorkspaceScope,
  memberId: string,
  request: UpdateMemberDeactivationRequest,
) {
  const endpoint = workspaceEndpoints.updateMemberDeactivation
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    memberId,
  })
  return apiRequest<UpdateMemberDeactivationResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
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
    headers: contentCreationHeaders(scope, idempotencyKey),
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
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function prepareRoleHandoff(
  scope: WorkspaceScope,
  roleId: string,
  request: PrepareRoleHandoffRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.prepareRoleHandoff
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roleId,
  })
  return apiRequest<PrepareRoleHandoffResponse>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey),
    body: request,
  })
}

export function transferRoleHandoff(
  scope: WorkspaceScope,
  roleId: string,
  handoffId: string,
  request: TransferRoleHandoffRequest,
) {
  const endpoint = workspaceEndpoints.transferRoleHandoff
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roleId,
    handoffId,
  })
  return apiRequest<TransferRoleHandoffResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function acceptRoleHandoff(
  scope: WorkspaceScope,
  roleId: string,
  handoffId: string,
  request: ConfirmRoleHandoffRequest,
) {
  const endpoint = workspaceEndpoints.acceptRoleHandoff
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roleId,
    handoffId,
  })
  return apiRequest<AcceptRoleHandoffResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function cancelRoleHandoff(
  scope: WorkspaceScope,
  roleId: string,
  handoffId: string,
  request: CancelRoleHandoffRequest,
) {
  const endpoint = workspaceEndpoints.cancelRoleHandoff
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    roleId,
    handoffId,
  })
  return apiRequest<CancelRoleHandoffResponse>(path, {
    method: endpoint.method,
    headers: scopedHeaders(scope),
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
    headers: contentCreationHeaders(scope, idempotencyKey),
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
    headers: scopedHeaders(scope),
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
    headers: contentCreationHeaders(scope, idempotencyKey),
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
    headers: scopedHeaders(scope),
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
    headers: scopedHeaders(scope),
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
    headers: scopedHeaders(scope),
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
    headers: contentCreationHeaders(scope, idempotencyKey),
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
    headers: scopedHeaders(scope),
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
    headers: scopedHeaders(scope),
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
    headers: contentCreationHeaders(scope, idempotencyKey),
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
    headers: scopedHeaders(scope),
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
    headers: scopedHeaders(scope),
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
    headers: scopedHeaders(scope),
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
    headers: contentCreationHeaders(scope, idempotencyKey),
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
    headers: scopedHeaders(scope),
    body: request,
  })
}

export function openRoleResourceLink(
  scope: WorkspaceScope,
  resourceId: string,
  request: OpenRoleResourceLinkRequest,
  idempotencyKey: string,
) {
  const endpoint = workspaceEndpoints.openRoleResourceLink
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    resourceId,
  })
  return apiRequest<OpenRoleResourceLinkResponse>(path, {
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey),
    body: request,
  })
}
