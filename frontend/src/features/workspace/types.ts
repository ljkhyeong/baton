import type { operations } from '@/generated/api'

export type ViewKey = 'today' | 'roles' | 'rhythm' | 'memory' | 'handoff'

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
type ApiRole = JsonResponse<'createRole', 201>

type ExplicitRoleAssignmentFields = {
  currentMemberId: string | null
  nextMemberId: string | null
  assignmentStartDate: string | null
  assignmentEndDate: string | null
  risk: string | null
}

export type Team = ApiWorkspaceProjection['team']
export type Season = ApiWorkspaceProjection['season']
export type Member = ApiWorkspaceProjection['members'][number]
export type Role = ApiRole
export type Routine = JsonResponse<'createRoutine', 201>
export type Decision = JsonResponse<'createDecision', 201>
export type HandoffItem = JsonResponse<'createHandoffItem', 201>
export type UpdateRoleResponse = JsonResponse<'updateRole', 200>
export type UpdateRoutineResponse = JsonResponse<'updateRoutine', 200>
export type UpdateRoutineCompletionResponse = JsonResponse<'updateRoutineCompletion', 200>
export type UpdateHandoffItemCompletionResponse = JsonResponse<
  'updateHandoffItemCompletion',
  200
>

export type RoutinePhase = Routine['phase']
export type RoutineStatus = Routine['status']
export type HandoffCategory = HandoffItem['category']

export type WorkspaceProjection = ApiWorkspaceProjection

export type CreateWorkspaceRequest = JsonRequest<'createWorkspace'>
export type CreateWorkspaceResponse = JsonResponse<'createWorkspace', 201>
export type RotateAccessKeyResponse = JsonResponse<'rotateAccessKey', 200>

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

type ApiCreateDecisionRequest = JsonRequest<'createDecision'>

export type CreateDecisionRequest = Omit<ApiCreateDecisionRequest, 'alternative'> & {
  alternative: string
}

export type CreateHandoffItemRequest = JsonRequest<'createHandoffItem'>
export type UpdateRoutineCompletionRequest = JsonRequest<'updateRoutineCompletion'>
export type UpdateHandoffItemCompletionRequest = JsonRequest<'updateHandoffItemCompletion'>

export type CreateWorkspaceHeaders = operations['createWorkspace']['parameters']['header']
export type WorkspaceAccessHeaders = operations['getWorkspace']['parameters']['header']
export type RotateAccessKeyHeaders = operations['rotateAccessKey']['parameters']['header']
export type CreateRoleHeaders = operations['createRole']['parameters']['header']
export type UpdateRoleHeaders = operations['updateRole']['parameters']['header']
export type CreateRoutineHeaders = operations['createRoutine']['parameters']['header']
export type UpdateRoutineHeaders = operations['updateRoutine']['parameters']['header']
export type CreateDecisionHeaders = operations['createDecision']['parameters']['header']
export type CreateHandoffItemHeaders = operations['createHandoffItem']['parameters']['header']
export type UpdateRoutineCompletionHeaders =
  operations['updateRoutineCompletion']['parameters']['header']
export type UpdateHandoffItemCompletionHeaders =
  operations['updateHandoffItemCompletion']['parameters']['header']
