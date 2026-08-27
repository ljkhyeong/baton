import { apiRequest } from '@/shared/api/client'
import {
  resolveEndpointPath,
  seasonLifecycleEndpoints,
  workspaceEndpoints,
} from './contract'
import {
  decodeCreateWorkspaceResponse,
  decodeRotateAccessKeyResponse,
} from './workspaceCredentialResponseDecoder'
import {
  decodeAcceptRoleHandoffResponse,
  decodeCancelRoleHandoffResponse,
  decodeCreateNextSeasonResponse,
  decodeDecision,
  decodeHandoffItem,
  decodeMember,
  decodePrepareRoleHandoffResponse,
  decodeRole,
  decodeRoleResource,
  decodeRoutine,
  decodeRoutineExecution,
  decodeSeasonRound,
  decodeSeasonSummary,
  decodeTransferRoleHandoffResponse,
  decodeWorkspaceProjectionForScope,
} from './workspaceProjectionDecoder'
import type {
  AcceptRoleHandoffHeaders,
  AcceptRoleHandoffResponse,
  CancelRoleHandoffRequest,
  CancelRoleHandoffHeaders,
  CancelRoleHandoffResponse,
  ConfirmRoleHandoffRequest,
  CreateNextSeasonHeaders,
  CreateNextSeasonRequest,
  CreateNextSeasonResponse,
  CreateDecisionHeaders,
  CreateDecisionRequest,
  CreateHandoffItemHeaders,
  CreateHandoffItemRequest,
  CreateMemberHeaders,
  CreateMemberRequest,
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
  Member,
  PrepareRoleHandoffHeaders,
  PrepareRoleHandoffRequest,
  PrepareRoleHandoffResponse,
  Role,
  RoleResource,
  RotateAccessKeyResponse,
  RotateAccessKeyHeaders,
  Routine,
  RoutineExecution,
  SeasonAccessHeaders,
  SeasonRound,
  SeasonSummary,
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
  UpdateMemberDeactivationHeaders,
  UpdateMemberDeactivationRequest,
  UpdateMemberDeactivationResponse,
  UpdateMemberHeaders,
  UpdateMemberRequest,
  UpdateMemberResponse,
  UpdateRoutineHeaders,
  UpdateRoutineArchiveHeaders,
  UpdateRoutineArchiveRequest,
  UpdateRoutineArchiveResponse,
  UpdateRoutineRequest,
  UpdateRoutineResponse,
  UpdateRoutineExecutionCompletionHeaders,
  UpdateRoutineExecutionCompletionRequest,
  UpdateRoundScheduleHeaders,
  UpdateRoundScheduleRequest,
  UpdateSeasonEndingRequest,
  UpdateSeasonRequest,
  UpdateSeasonRoundArchiveHeaders,
  UpdateSeasonRoundArchiveRequest,
  UpdateSeasonRoundArchiveResponse,
  UpdateSeasonRoundHeaders,
  UpdateSeasonRoundRequest,
  UpdateSeasonRoundResponse,
  TransferRoleHandoffHeaders,
  TransferRoleHandoffRequest,
  TransferRoleHandoffResponse,
  WorkspaceAccessHeaders,
  WorkspaceProjection,
} from './types'

export type WorkspaceScope = {
  teamId: string
  seasonId: string
  accessKey: string
}

type CreateWorkspaceOptions = {
  idempotencyKey: string
  creationKey?: string
}

export function createWorkspace(request: CreateWorkspaceRequest, options: CreateWorkspaceOptions) {
  const endpoint = workspaceEndpoints.createWorkspace
  return apiRequest<CreateWorkspaceResponse>(endpoint.path, {
    decode: decodeCreateWorkspaceResponse,
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

export function getWorkspace(scope: WorkspaceScope, signal?: AbortSignal) {
  const endpoint = workspaceEndpoints.getWorkspace
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<WorkspaceProjection>(path, {
    decode: (value) => decodeWorkspaceProjectionForScope(value, scope),
    method: endpoint.method,
    headers: scopedHeaders(scope),
    signal,
  })
}

export function rotateAccessKey(scope: WorkspaceScope, idempotencyKey: string) {
  const endpoint = workspaceEndpoints.rotateAccessKey
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<RotateAccessKeyResponse>(path, {
    decode: decodeRotateAccessKeyResponse,
    method: endpoint.method,
    headers: {
      ...scopedHeaders(scope),
      'Idempotency-Key': idempotencyKey,
    } satisfies RotateAccessKeyHeaders,
  })
}

export function updateSeason(scope: WorkspaceScope, request: UpdateSeasonRequest) {
  const endpoint = seasonLifecycleEndpoints.updateSeason
  const path = resolveEndpointPath(endpoint, scopedParameters(scope))
  return apiRequest<SeasonSummary>(path, {
    decode: decodeSeasonSummary,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies SeasonAccessHeaders,
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
    decode: decodeSeasonSummary,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoundScheduleHeaders,
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
    decode: decodeSeasonSummary,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies SeasonAccessHeaders,
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
    decode: (value) => decodeCreateNextSeasonResponse(value, scope.seasonId),
    method: endpoint.method,
    headers: {
      ...scopedHeaders(scope),
      'Idempotency-Key': idempotencyKey,
    } satisfies CreateNextSeasonHeaders,
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
    decode: decodeMember,
    method: endpoint.method,
    headers: contentCreationHeaders(scope, idempotencyKey) satisfies CreateMemberHeaders,
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
    decode: decodeMember,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateMemberHeaders,
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
    decode: decodeMember,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateMemberDeactivationHeaders,
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
    decode: decodeRole,
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
    decode: decodeRole,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoleHeaders,
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
    decode: (value) => decodePrepareRoleHandoffResponse(value, roleId),
    method: endpoint.method,
    headers: contentCreationHeaders(
      scope,
      idempotencyKey,
    ) satisfies PrepareRoleHandoffHeaders,
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
    decode: (value) => decodeTransferRoleHandoffResponse(value, roleId, handoffId),
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies TransferRoleHandoffHeaders,
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
    decode: (value) => decodeAcceptRoleHandoffResponse(value, roleId, handoffId),
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies AcceptRoleHandoffHeaders,
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
    decode: (value) => decodeCancelRoleHandoffResponse(value, roleId, handoffId),
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies CancelRoleHandoffHeaders,
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
    decode: decodeRoutine,
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
    decode: decodeRoutine,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoutineHeaders,
    body: request,
  })
}

export function setRoutineArchived(
  scope: WorkspaceScope,
  routineId: string,
  archived: boolean,
) {
  const endpoint = workspaceEndpoints.updateRoutineArchive
  const body: UpdateRoutineArchiveRequest = { archived }
  const path = resolveEndpointPath(endpoint, {
    teamId: scope.teamId,
    seasonId: scope.seasonId,
    routineId,
  })
  return apiRequest<UpdateRoutineArchiveResponse>(path, {
    decode: decodeRoutine,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoutineArchiveHeaders,
    body,
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
    decode: decodeSeasonRound,
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
    decode: decodeSeasonRound,
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
    decode: decodeSeasonRound,
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
    decode: decodeRoutineExecution,
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
    decode: decodeDecision,
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
    decode: decodeDecision,
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
    decode: decodeDecision,
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
    decode: decodeHandoffItem,
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
    decode: decodeHandoffItem,
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
    decode: decodeHandoffItem,
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
    decode: decodeHandoffItem,
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
    decode: decodeRoleResource,
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
    decode: decodeRoleResource,
    method: endpoint.method,
    headers: scopedHeaders(scope) satisfies UpdateRoleResourceHeaders,
    body: request,
  })
}
