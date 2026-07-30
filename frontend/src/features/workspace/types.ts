import type { operations } from '@/generated/api'

export type ViewKey = 'today' | 'roles' | 'rhythm' | 'memory' | 'handoff' | 'records'

type OperationId = keyof operations

type JsonRequest<Id extends OperationId> = NonNullable<operations[Id]['requestBody']> extends {
  content: { 'application/json': infer Body }
}
  ? Body
  : never

type JsonResponse<
  Id extends OperationId,
  Status extends keyof operations[Id]['responses'],
> = operations[Id]['responses'][Status] extends {
  content: { 'application/json': infer Body }
}
  ? Body
  : never

type ApiWorkspaceProjection = JsonResponse<'getWorkspace', 200>
type ApiMember = JsonResponse<'createMember', 201>
type ApiRole = JsonResponse<'createRole', 201>

export type SeasonSummary = ApiWorkspaceProjection['seasons'][number]
export type UpdateSeasonRequest = JsonRequest<'updateSeason'>
export type UpdateRoundScheduleRequest = JsonRequest<'updateRoundSchedule'>
export type UpdateSeasonEndingRequest = JsonRequest<'updateSeasonEnding'>
export type CreateNextSeasonRequest = JsonRequest<'createNextSeason'>
export type CreateNextSeasonResponse = JsonResponse<'createNextSeason', 201>

type ExplicitRoleAssignmentFields = {
  currentMemberId: string | null
  nextMemberId: string | null
  assignmentStartDate: string | null
  assignmentEndDate: string | null
  risk: string | null
}

export type Team = ApiWorkspaceProjection['team']
export type Season = SeasonSummary
export type Member = ApiMember
export type Role = ApiRole
export type Routine = JsonResponse<'createRoutine', 201>
export type SeasonRound = JsonResponse<'createSeasonRound', 201>
export type RoutineExecution = JsonResponse<'updateRoutineExecutionCompletion', 200>
export type Decision = JsonResponse<'createDecision', 201>
export type HandoffItem = JsonResponse<'createHandoffItem', 201>
export type RoleHandoff = ApiWorkspaceProjection['roleHandoffs'][number]
export type ContinuitySignal = ApiWorkspaceProjection['continuitySignals'][number]
export type PrepareRoleHandoffResponse = JsonResponse<'prepareRoleHandoff', 201>
export type TransferRoleHandoffResponse = JsonResponse<'transferRoleHandoff', 200>
export type AcceptRoleHandoffResponse = JsonResponse<'acceptRoleHandoff', 200>
export type CancelRoleHandoffResponse = JsonResponse<'cancelRoleHandoff', 200>
export type RoleHandoffTransitionResponse =
  | PrepareRoleHandoffResponse
  | TransferRoleHandoffResponse
  | AcceptRoleHandoffResponse
  | CancelRoleHandoffResponse
export type RoleResource = JsonResponse<'createRoleResource', 201>
export type UpdateMemberResponse = JsonResponse<'updateMember', 200>
export type UpdateMemberDeactivationResponse = JsonResponse<'updateMemberDeactivation', 200>
export type UpdateRoleResponse = JsonResponse<'updateRole', 200>
export type UpdateRoutineResponse = JsonResponse<'updateRoutine', 200>
export type UpdateSeasonRoundResponse = JsonResponse<'updateSeasonRound', 200>
export type UpdateSeasonRoundArchiveResponse = JsonResponse<'updateSeasonRoundArchive', 200>
export type UpdateDecisionResponse = JsonResponse<'updateDecision', 200>
export type UpdateDecisionArchiveResponse = JsonResponse<'updateDecisionArchive', 200>
export type UpdateHandoffItemResponse = JsonResponse<'updateHandoffItem', 200>
export type UpdateHandoffItemCompletionResponse = JsonResponse<
  'updateHandoffItemCompletion',
  200
>
export type UpdateHandoffItemArchiveResponse = JsonResponse<'updateHandoffItemArchive', 200>
export type UpdateRoleResourceResponse = JsonResponse<'updateRoleResource', 200>

export type RoutinePhase = Routine['phase']
export type RoutineStatus = RoutineExecution['status']
export type RoutineTimingStatus = RoutineExecution['timingStatus']
export type RoundTimingStatus = SeasonRound['timingStatus']
export type RoundOrigin = SeasonRound['origin']
export type HandoffCategory = HandoffItem['category']
export type RoleHandoffStatus = RoleHandoff['status']

export type WorkspaceProjection = ApiWorkspaceProjection

export type CreateWorkspaceRequest = JsonRequest<'createWorkspace'>
export type CreateWorkspaceResponse = JsonResponse<'createWorkspace', 201>
export type RotateAccessKeyResponse = JsonResponse<'rotateAccessKey', 200>
export type CreateMemberRequest = JsonRequest<'createMember'>
export type UpdateMemberRequest = JsonRequest<'updateMember'>
export type UpdateMemberDeactivationRequest = JsonRequest<'updateMemberDeactivation'>

type ApiCreateRoleRequest = JsonRequest<'createRole'>
type ApiUpdateRoleRequest = JsonRequest<'updateRole'>

export type CreateRoleRequest = Omit<
  ApiCreateRoleRequest,
  keyof ExplicitRoleAssignmentFields
> &
  ExplicitRoleAssignmentFields

export type UpdateRoleRequest = Omit<
  ApiUpdateRoleRequest,
  keyof ExplicitRoleAssignmentFields
> &
  ExplicitRoleAssignmentFields

export type CreateRoutineRequest = JsonRequest<'createRoutine'>
export type UpdateRoutineRequest = JsonRequest<'updateRoutine'>
export type CreateSeasonRoundRequest = JsonRequest<'createSeasonRound'>
export type UpdateSeasonRoundRequest = JsonRequest<'updateSeasonRound'>
export type UpdateSeasonRoundArchiveRequest = JsonRequest<'updateSeasonRoundArchive'>

type ApiCreateDecisionRequest = JsonRequest<'createDecision'>
type ApiUpdateDecisionRequest = JsonRequest<'updateDecision'>

export type CreateDecisionRequest = Omit<ApiCreateDecisionRequest, 'alternative'> & {
  alternative: string
}

export type UpdateDecisionRequest = Omit<ApiUpdateDecisionRequest, 'alternative'> & {
  alternative: string
}

export type CreateHandoffItemRequest = JsonRequest<'createHandoffItem'>
export type UpdateHandoffItemRequest = JsonRequest<'updateHandoffItem'>
export type PrepareRoleHandoffRequest = JsonRequest<'prepareRoleHandoff'>
export type PrepareRoleHandoffCommandRequest = PrepareRoleHandoffRequest & {
  roleId: string
}
export type TransferRoleHandoffRequest = JsonRequest<'transferRoleHandoff'>
export type ConfirmRoleHandoffRequest = JsonRequest<'acceptRoleHandoff'>
export type CancelRoleHandoffRequest = JsonRequest<'cancelRoleHandoff'>
export type UpdateRecordArchiveRequest = JsonRequest<'updateDecisionArchive'>
export type CreateRoleResourceRequest = JsonRequest<'createRoleResource'>
export type UpdateRoleResourceRequest = JsonRequest<'updateRoleResource'>
export type UpdateRoutineExecutionCompletionRequest = JsonRequest<
  'updateRoutineExecutionCompletion'
>
export type UpdateHandoffItemCompletionRequest = JsonRequest<'updateHandoffItemCompletion'>

export type CreateWorkspaceHeaders = operations['createWorkspace']['parameters']['header']
export type WorkspaceAccessHeaders = operations['getWorkspace']['parameters']['header']
export type RotateAccessKeyHeaders = operations['rotateAccessKey']['parameters']['header']
export type SeasonAccessHeaders = operations['updateSeason']['parameters']['header']
export type UpdateRoundScheduleHeaders =
  operations['updateRoundSchedule']['parameters']['header']
export type CreateNextSeasonHeaders = operations['createNextSeason']['parameters']['header']
export type CreateMemberHeaders = operations['createMember']['parameters']['header']
export type UpdateMemberHeaders = operations['updateMember']['parameters']['header']
export type UpdateMemberDeactivationHeaders =
  operations['updateMemberDeactivation']['parameters']['header']
export type CreateRoleHeaders = operations['createRole']['parameters']['header']
export type UpdateRoleHeaders = operations['updateRole']['parameters']['header']
export type CreateRoutineHeaders = operations['createRoutine']['parameters']['header']
export type UpdateRoutineHeaders = operations['updateRoutine']['parameters']['header']
export type CreateSeasonRoundHeaders = operations['createSeasonRound']['parameters']['header']
export type UpdateSeasonRoundHeaders = operations['updateSeasonRound']['parameters']['header']
export type UpdateSeasonRoundArchiveHeaders =
  operations['updateSeasonRoundArchive']['parameters']['header']
export type CreateDecisionHeaders = operations['createDecision']['parameters']['header']
export type UpdateDecisionHeaders = operations['updateDecision']['parameters']['header']
export type UpdateDecisionArchiveHeaders =
  operations['updateDecisionArchive']['parameters']['header']
export type CreateHandoffItemHeaders = operations['createHandoffItem']['parameters']['header']
export type UpdateHandoffItemHeaders = operations['updateHandoffItem']['parameters']['header']
export type UpdateHandoffItemArchiveHeaders =
  operations['updateHandoffItemArchive']['parameters']['header']
export type UpdateRoutineExecutionCompletionHeaders =
  operations['updateRoutineExecutionCompletion']['parameters']['header']
export type UpdateHandoffItemCompletionHeaders =
  operations['updateHandoffItemCompletion']['parameters']['header']
export type PrepareRoleHandoffHeaders =
  operations['prepareRoleHandoff']['parameters']['header']
export type TransferRoleHandoffHeaders =
  operations['transferRoleHandoff']['parameters']['header']
export type AcceptRoleHandoffHeaders =
  operations['acceptRoleHandoff']['parameters']['header']
export type CancelRoleHandoffHeaders =
  operations['cancelRoleHandoff']['parameters']['header']
export type CreateRoleResourceHeaders = operations['createRoleResource']['parameters']['header']
export type UpdateRoleResourceHeaders = operations['updateRoleResource']['parameters']['header']
