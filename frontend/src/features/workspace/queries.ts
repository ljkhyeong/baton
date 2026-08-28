import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
  detail: (teamId: string, seasonId: string, accessKey: string) =>
    ['teams', teamId, 'seasons', seasonId, 'workspace', { accessKey }] as const,
}

const WORKSPACE_MUTATION_KEY_PREFIX = 'workspace-mutation'

function workspaceMutationKey(
  scope: Pick<WorkspaceScope, 'teamId' | 'seasonId'>,
) {
  return [WORKSPACE_MUTATION_KEY_PREFIX, scope.teamId, scope.seasonId] as const
}

export function isWorkspaceMutationForScope(
  mutationKey: readonly unknown[] | undefined,
  scope: Pick<WorkspaceScope, 'teamId' | 'seasonId'>,
) {
  const expected = workspaceMutationKey(scope)
  return mutationKey?.length === expected.length
    && mutationKey.every((value, index) => value === expected[index])
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

function isWorkspaceAccessDeniedError(error: unknown) {
  return error instanceof ApiError && error.code === 'WORKSPACE_ACCESS_DENIED'
}

function canAutomaticallyRefetchWorkspace(query: { state: { error: unknown } }) {
  return !isWorkspaceAccessDeniedError(query.state.error)
}

export function useWorkspaceQuery(scope: WorkspaceScope) {
  return useQuery({
    queryKey: workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey),
    queryFn: ({ signal }) => getWorkspace(scope, signal),
    enabled: Boolean(scope.teamId && scope.seasonId && scope.accessKey),
    retry: (failureCount, error) => !isWorkspaceAccessDeniedError(error)
      && failureCount < 1,
    refetchInterval: (query) => !canAutomaticallyRefetchWorkspace(query)
      ? false
      : WORKSPACE_SYNC_INTERVAL_MS,
    refetchIntervalInBackground: false,
    refetchOnReconnect: (query) => canAutomaticallyRefetchWorkspace(query) && 'always',
    refetchOnWindowFocus: (query) => canAutomaticallyRefetchWorkspace(query) && 'always',
  })
}

function useInvalidateWorkspace(scope: WorkspaceScope) {
  const queryClient = useQueryClient()
  const queryKey = workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey)

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
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: (request: UpdateSeasonRequest) => updateSeason(scope, request),
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateRoundScheduleMutation(scope: WorkspaceScope) {
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: (request: UpdateRoundScheduleRequest) =>
      updateRoundSchedule(scope, request),
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateSeasonEndingMutation(scope: WorkspaceScope) {
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: (request: UpdateSeasonEndingRequest) =>
      updateSeasonEnding(scope, request),
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useCreateNextSeasonMutation(scope: WorkspaceScope) {
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({
      request,
      idempotencyKey,
    }: IdempotentCreateCommand<CreateNextSeasonRequest>) =>
      createNextSeason(scope, request, idempotencyKey),
    onSettled: invalidateTeam,
  })
}

export function useCreateRoleMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoleRequest>) =>
      createRole(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useCreateMemberMutation(scope: WorkspaceScope) {
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateMemberRequest>) =>
      createMember(scope, request, idempotencyKey),
    onSettled: invalidateTeam,
  })
}

export function useUpdateMemberMutation(scope: WorkspaceScope) {
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateMemberRequest>) =>
      updateMember(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateMemberDeactivationMutation(scope: WorkspaceScope) {
  const { invalidateTeam } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateMemberDeactivationRequest>) =>
      updateMemberDeactivation(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateRoleMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateRoleRequest>) =>
      updateRole(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function usePrepareRoleHandoffMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({
      request,
      idempotencyKey,
    }: IdempotentCreateCommand<PrepareRoleHandoffCommandRequest>) => {
      const { roleId, ...body } = request
      return prepareRoleHandoff(scope, roleId, body, idempotencyKey)
    },
    onSettled: invalidate,
  })
}

export function useTransferRoleHandoffMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({
      roleId,
      handoffId,
      request,
    }: RoleHandoffTransitionCommand<TransferRoleHandoffRequest>) =>
      transferRoleHandoff(scope, roleId, handoffId, request),
    onSettled: invalidate,
  })
}

export function useAcceptRoleHandoffMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({
      roleId,
      handoffId,
      request,
    }: RoleHandoffTransitionCommand<ConfirmRoleHandoffRequest>) =>
      acceptRoleHandoff(scope, roleId, handoffId, request),
    onSettled: invalidate,
  })
}

export function useCancelRoleHandoffMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({
      roleId,
      handoffId,
      request,
    }: RoleHandoffTransitionCommand<CancelRoleHandoffRequest>) =>
      cancelRoleHandoff(scope, roleId, handoffId, request),
    onSettled: invalidate,
  })
}

export function useCreateRoutineMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoutineRequest>) =>
      createRoutine(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateRoutineMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateRoutineRequest>) =>
      updateRoutine(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useRoutineArchiveMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, archived }: ArchiveCommand) =>
      setRoutineArchived(scope, id, archived),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateSeasonRoundMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateSeasonRoundRequest>) =>
      createSeasonRound(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateSeasonRoundMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateSeasonRoundRequest>) =>
      updateSeasonRound(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useSeasonRoundArchiveMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, archived }: ArchiveCommand) =>
      setSeasonRoundArchived(scope, id, archived),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useRoutineExecutionCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ roundId, executionId, completed }: {
      roundId: string
      executionId: string
      completed: boolean
    }) => setRoutineExecutionCompletion(scope, roundId, executionId, completed),
    onMutate: async ({ roundId, executionId, completed }) => {
      await queryClient.cancelQueries({ queryKey })
      const previousStatus = queryClient.getQueryData<WorkspaceProjection>(queryKey)?.rounds
        .find((round) => round.id === roundId)?.routineExecutions
        .find((execution) => execution.id === executionId)?.status
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              rounds: current.rounds.map((round) =>
                round.id === roundId
                  ? {
                      ...round,
                      routineExecutions: round.routineExecutions.map((execution) =>
                        execution.id === executionId
                          ? { ...execution, status: completed ? 'DONE' : 'WAITING' }
                          : execution,
                      ),
                    }
                  : round,
              ),
            }
          : current,
      )
      return { previousStatus }
    },
    onError: (_error, { roundId, executionId }, context) => {
      const previousStatus = context?.previousStatus
      if (!previousStatus) return
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              rounds: current.rounds.map((round) =>
                round.id === roundId
                  ? {
                      ...round,
                      routineExecutions: round.routineExecutions.map((execution) =>
                        execution.id === executionId
                          ? { ...execution, status: previousStatus }
                          : execution,
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
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateDecisionRequest>) =>
      createDecision(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateDecisionMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateDecisionRequest>) =>
      updateDecision(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useDecisionArchiveMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, archived }: ArchiveCommand) => setDecisionArchived(scope, id, archived),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateHandoffItemMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateHandoffItemRequest>) =>
      createHandoffItem(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateHandoffItemMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateHandoffItemRequest>) =>
      updateHandoffItem(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useHandoffCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, completed }: { id: string; completed: boolean }) =>
      setHandoffItemCompletion(scope, id, completed),
    onMutate: async ({ id, completed }) => {
      await queryClient.cancelQueries({ queryKey })
      const previousCompleted = queryClient.getQueryData<WorkspaceProjection>(queryKey)
        ?.handoffItems.find((item) => item.id === id)?.completed
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
      return { previousCompleted }
    },
    onError: (_error, { id }, context) => {
      const previousCompleted = context?.previousCompleted
      if (previousCompleted === undefined) return
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              handoffItems: current.handoffItems.map((item) =>
                item.id === id ? { ...item, completed: previousCompleted } : item,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useHandoffItemArchiveMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, archived }: ArchiveCommand) =>
      setHandoffItemArchived(scope, id, archived),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateRoleResourceMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoleResourceRequest>) =>
      createRoleResource(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateRoleResourceMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, request }: UpdateCommand<UpdateRoleResourceRequest>) =>
      updateRoleResource(scope, id, request),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useRoleResourceArchiveMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useWorkspaceMutation(scope, {
    mutationFn: ({ id, archived }: ArchiveCommand) =>
      setRoleResourceArchived(scope, id, archived),
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}
