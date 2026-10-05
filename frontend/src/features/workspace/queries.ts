import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationOptions } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import {
  acceptRoleHandoff,
  cancelRoleHandoff,
  createDecision,
  createHandoffItem,
  createMember,
  createNextSeason,
  createRole,
  createRoleResource,
  createRoutine,
  createSeasonRound,
  getWorkspace,
  isWorkspaceAccessDenied,
  prepareRoleHandoff,
  rotateAccessKey,
  setDecisionArchived,
  setHandoffItemArchived,
  setHandoffItemCompletion,
  setRoleResourceArchived,
  setRoutineArchived,
  setRoutineExecutionCompletion,
  setSeasonRoundArchived,
  transferRoleHandoff,
  updateDecision,
  updateHandoffItem,
  updateMember,
  updateMemberDeactivation,
  updateRole,
  updateRoleResource,
  updateRoutine,
  updateRoundSchedule,
  updateSeason,
  updateSeasonEnding,
  updateSeasonRound,
} from './api'
import type { WorkspaceScope } from './api'
import type {
  CancelRoleHandoffRequest,
  ConfirmRoleHandoffRequest,
  CreateNextSeasonRequest,
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateMemberRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  PrepareRoleHandoffCommandRequest,
  UpdateDecisionRequest,
  UpdateHandoffItemRequest,
  UpdateMemberDeactivationRequest,
  UpdateMemberRequest,
  UpdateRoleRequest,
  UpdateRoleResourceRequest,
  UpdateRoutineRequest,
  UpdateRoundScheduleRequest,
  UpdateSeasonEndingRequest,
  UpdateSeasonRequest,
  UpdateSeasonRoundRequest,
  TransferRoleHandoffRequest,
  WorkspaceProjection,
} from './types'

export type IdempotentCreateCommand<TRequest> = {
  request: TRequest
  idempotencyKey: string
}

type UpdateCommand<TRequest> = {
  id: string
  request: TRequest
}

type ArchiveCommand = {
  id: string
  archived: boolean
}

type RoleHandoffTransitionCommand<TRequest> = {
  roleId: string
  handoffId: string
  request: TRequest
}

export const workspaceKeys = {
  all: ['teams'] as const,
  team: (teamId: string) => [...workspaceKeys.all, teamId] as const,
  detail: (teamId: string, seasonId: string, accessKey: string, accountId = 'anonymous') =>
    ['teams', teamId, 'seasons', seasonId, 'workspace', { accessKey, accountId }] as const,
}

const WORKSPACE_MUTATION_KEY_PREFIX = 'workspace-mutation'

export function workspaceMutationKey(
  scope: Pick<WorkspaceScope, 'teamId' | 'seasonId'>,
) {
  return [WORKSPACE_MUTATION_KEY_PREFIX, scope.teamId, scope.seasonId] as const
}

function useWorkspaceMutation<
  TData = unknown,
  TError = Error,
  TVariables = void,
  TOnMutateResult = unknown,
>(
  scope: WorkspaceScope,
  options: UseMutationOptions<TData, TError, TVariables, TOnMutateResult>,
) {
  return useMutation({
    ...options,
    mutationKey: workspaceMutationKey(scope),
  })
}

const configuredWorkspaceSyncInterval = Number(import.meta.env.VITE_WORKSPACE_SYNC_INTERVAL_MS)
const WORKSPACE_SYNC_INTERVAL_MS = Number.isFinite(configuredWorkspaceSyncInterval)
  && configuredWorkspaceSyncInterval >= 1_000
  ? configuredWorkspaceSyncInterval
  : 10_000

function canAutomaticallyRefetchWorkspace(query: { state: { error: unknown } }) {
  return !isWorkspaceAccessDenied(query.state.error)
}

// 같은 캐시 키를 쓰는 작업 공간·기록 검색·모든 팀의 할 일이 같은 재시도·재조회 규칙을 쓴다.
export function workspaceQueryOptions(scope: WorkspaceScope, refetchInterval = WORKSPACE_SYNC_INTERVAL_MS) {
  return queryOptions({
    queryKey: workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey, scope.accountId),
    queryFn: ({ signal }) => getWorkspace(scope, signal),
    retry: (failureCount, error) => !isWorkspaceAccessDenied(error) && failureCount < 1,
    refetchInterval: (query) => canAutomaticallyRefetchWorkspace(query) && refetchInterval,
    refetchOnReconnect: (query) => canAutomaticallyRefetchWorkspace(query) && 'always',
    refetchOnWindowFocus: (query) => canAutomaticallyRefetchWorkspace(query) && 'always',
  })
}

export function useWorkspaceQuery(scope: WorkspaceScope) {
  return useQuery({
    ...workspaceQueryOptions(scope),
    enabled: Boolean(scope.teamId && scope.seasonId),
  })
}

function useInvalidateWorkspace(scope: WorkspaceScope) {
  const queryClient = useQueryClient()
  const queryKey = workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey, scope.accountId)

  return {
    queryClient,
    queryKey,
    invalidate: () => queryClient.invalidateQueries({ queryKey }),
    invalidateTeam: () => queryClient.invalidateQueries({
      queryKey: workspaceKeys.team(scope.teamId),
    }),
  }
}

function invalidateUnlessContentConflict(invalidate: () => Promise<void>) {
  return (_data: unknown, error: unknown) => {
    if (error instanceof ApiError && error.code === 'WORKSPACE_CONTENT_CONFLICT') return
    return invalidate()
  }
}

type WorkspaceCommandOptions = {
  // 시즌 목록·구성원처럼 팀의 다른 시즌 화면에도 보이는 변경
  teamWide?: boolean
  // 수정 충돌이면 충돌 복구 흐름이 최신 내용을 보여 줄 때까지 다시 불러오지 않는다.
  keepOnContentConflict?: boolean
}

function useWorkspaceCommand<TVariables, TData>(
  scope: WorkspaceScope,
  mutationFn: (variables: TVariables) => Promise<TData>,
  { teamWide = false, keepOnContentConflict = false }: WorkspaceCommandOptions = {},
) {
  const { invalidate, invalidateTeam } = useInvalidateWorkspace(scope)
  const refresh = teamWide ? invalidateTeam : invalidate
  return useWorkspaceMutation<TData, Error, TVariables>(scope, {
    mutationFn,
    onSettled: keepOnContentConflict ? invalidateUnlessContentConflict(refresh) : refresh,
  })
}

export function useRotateAccessKeyMutation(scope: WorkspaceScope) {
  const queryClient = useQueryClient()

  return useWorkspaceMutation(scope, {
    mutationFn: (idempotencyKey: string) => rotateAccessKey(scope, idempotencyKey),
    onSuccess: async ({ accessKey: rotatedAccessKey }) => {
      if (rotatedAccessKey === scope.accessKey) return
      await queryClient.cancelQueries({
        queryKey: workspaceKeys.team(scope.teamId),
      })
      queryClient.removeQueries({
        queryKey: workspaceKeys.team(scope.teamId),
      })
    },
  })
}

export function useUpdateSeasonMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    (request: UpdateSeasonRequest) => updateSeason(scope, request),
    { teamWide: true, keepOnContentConflict: true },
  )
}

export function useUpdateRoundScheduleMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    (request: UpdateRoundScheduleRequest) => updateRoundSchedule(scope, request),
    { teamWide: true, keepOnContentConflict: true },
  )
}

export function useUpdateSeasonEndingMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    (request: UpdateSeasonEndingRequest) => updateSeasonEnding(scope, request),
    { teamWide: true, keepOnContentConflict: true },
  )
}

export function useCreateNextSeasonMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateNextSeasonRequest>) =>
      createNextSeason(scope, request, idempotencyKey),
    { teamWide: true },
  )
}

export function useCreateRoleMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoleRequest>) =>
      createRole(scope, request, idempotencyKey),
  )
}

export function useCreateMemberMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateMemberRequest>) =>
      createMember(scope, request, idempotencyKey),
    { teamWide: true },
  )
}

export function useUpdateMemberMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateMemberRequest>) => updateMember(scope, id, request),
    { teamWide: true, keepOnContentConflict: true },
  )
}

export function useUpdateMemberDeactivationMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateMemberDeactivationRequest>) =>
      updateMemberDeactivation(scope, id, request),
    { teamWide: true, keepOnContentConflict: true },
  )
}

export function useUpdateRoleMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateRoleRequest>) => updateRole(scope, id, request),
    { keepOnContentConflict: true },
  )
}

export function usePrepareRoleHandoffMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({
      request: { roleId, ...body },
      idempotencyKey,
    }: IdempotentCreateCommand<PrepareRoleHandoffCommandRequest>) =>
      prepareRoleHandoff(scope, roleId, body, idempotencyKey),
  )
}

export function useTransferRoleHandoffMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ roleId, handoffId, request }: RoleHandoffTransitionCommand<TransferRoleHandoffRequest>) =>
      transferRoleHandoff(scope, roleId, handoffId, request),
  )
}

export function useAcceptRoleHandoffMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ roleId, handoffId, request }: RoleHandoffTransitionCommand<ConfirmRoleHandoffRequest>) =>
      acceptRoleHandoff(scope, roleId, handoffId, request),
  )
}

export function useCancelRoleHandoffMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ roleId, handoffId, request }: RoleHandoffTransitionCommand<CancelRoleHandoffRequest>) =>
      cancelRoleHandoff(scope, roleId, handoffId, request),
  )
}

export function useCreateRoutineMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoutineRequest>) =>
      createRoutine(scope, request, idempotencyKey),
  )
}

export function useUpdateRoutineMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateRoutineRequest>) => updateRoutine(scope, id, request),
    { keepOnContentConflict: true },
  )
}

export function useRoutineArchiveMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, archived }: ArchiveCommand) => setRoutineArchived(scope, id, archived),
    { keepOnContentConflict: true },
  )
}

export function useCreateSeasonRoundMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateSeasonRoundRequest>) =>
      createSeasonRound(scope, request, idempotencyKey),
  )
}

export function useUpdateSeasonRoundMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateSeasonRoundRequest>) =>
      updateSeasonRound(scope, id, request),
    { keepOnContentConflict: true },
  )
}

export function useSeasonRoundArchiveMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, archived }: ArchiveCommand) => setSeasonRoundArchived(scope, id, archived),
    { keepOnContentConflict: true },
  )
}

export function useRoutineExecutionCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ roundId, executionId, completed }: {
      roundId: string
      executionId: string
      completed: boolean
    }) => setRoutineExecutionCompletion(scope, roundId, executionId, completed),
    onSuccess: (updatedExecution) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              rounds: current.rounds.map((round) =>
                round.id === updatedExecution.roundId
                  ? {
                      ...round,
                      routineExecutions: round.routineExecutions.map((execution) =>
                        execution.id === updatedExecution.id ? updatedExecution : execution,
                      ),
                    }
                  : round,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateDecisionMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateDecisionRequest>) =>
      createDecision(scope, request, idempotencyKey),
  )
}

export function useUpdateDecisionMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateDecisionRequest>) => updateDecision(scope, id, request),
    { keepOnContentConflict: true },
  )
}

export function useDecisionArchiveMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, archived }: ArchiveCommand) => setDecisionArchived(scope, id, archived),
    { keepOnContentConflict: true },
  )
}

export function useCreateHandoffItemMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateHandoffItemRequest>) =>
      createHandoffItem(scope, request, idempotencyKey),
  )
}

export function useUpdateHandoffItemMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateHandoffItemRequest>) =>
      updateHandoffItem(scope, id, request),
    { keepOnContentConflict: true },
  )
}

export function useHandoffCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  const setCompleted = (id: string, completed: boolean) =>
    queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
      current
        ? {
            ...current,
            handoffItems: current.handoffItems.map((item) =>
              item.id === id ? { ...item, completed } : item,
            ),
          }
        : current,
    )

  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, completed }: { id: string; completed: boolean }) =>
      setHandoffItemCompletion(scope, id, completed),
    onMutate: async ({ id, completed }) => {
      await queryClient.cancelQueries({ queryKey })
      const previousCompleted = queryClient.getQueryData<WorkspaceProjection>(queryKey)
        ?.handoffItems.find((item) => item.id === id)?.completed
      setCompleted(id, completed)
      return { previousCompleted }
    },
    onError: (_error, { id }, context) => {
      const previousCompleted = context?.previousCompleted
      if (previousCompleted === undefined) return
      setCompleted(id, previousCompleted)
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useHandoffItemArchiveMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, archived }: ArchiveCommand) => setHandoffItemArchived(scope, id, archived),
    { keepOnContentConflict: true },
  )
}

export function useCreateRoleResourceMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoleResourceRequest>) =>
      createRoleResource(scope, request, idempotencyKey),
  )
}

export function useUpdateRoleResourceMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, request }: UpdateCommand<UpdateRoleResourceRequest>) =>
      updateRoleResource(scope, id, request),
    { keepOnContentConflict: true },
  )
}

export function useRoleResourceArchiveMutation(scope: WorkspaceScope) {
  return useWorkspaceCommand(
    scope,
    ({ id, archived }: ArchiveCommand) => setRoleResourceArchived(scope, id, archived),
    { keepOnContentConflict: true },
  )
}
