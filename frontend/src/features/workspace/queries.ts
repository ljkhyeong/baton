import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
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
  CreateNextSeasonResponse,
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateMemberRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  PrepareRoleHandoffCommandRequest,
  RoleHandoffTransitionResponse,
  SeasonSummary,
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

export type UpdateCommand<TRequest> = {
  id: string
  request: TRequest
}

export type ArchiveCommand = {
  id: string
  archived: boolean
}

export type RoleHandoffTransitionCommand<TRequest> = {
  roleId: string
  handoffId: string
  request: TRequest
}

export const workspaceKeys = {
  team: (teamId: string) => ['teams', teamId] as const,
  detail: (teamId: string, seasonId: string, accessKey: string) =>
    ['teams', teamId, 'seasons', seasonId, 'workspace', { accessKey }] as const,
}

const configuredWorkspaceSyncInterval = Number(import.meta.env.VITE_WORKSPACE_SYNC_INTERVAL_MS)
const WORKSPACE_SYNC_INTERVAL_MS = Number.isFinite(configuredWorkspaceSyncInterval)
  && configuredWorkspaceSyncInterval >= 1_000
  ? configuredWorkspaceSyncInterval
  : 10_000

export function useWorkspaceQuery(scope: WorkspaceScope) {
  const query = useQuery({
    queryKey: workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey),
    queryFn: () => getWorkspace(scope),
    enabled: Boolean(scope.teamId && scope.seasonId && scope.accessKey),
    refetchInterval: (query) => query.state.error instanceof ApiError
      && query.state.error.code === 'WORKSPACE_ACCESS_DENIED'
      ? false
      : WORKSPACE_SYNC_INTERVAL_MS,
    refetchIntervalInBackground: false,
    refetchOnReconnect: 'always',
    refetchOnWindowFocus: 'always',
  })

  useEffect(() => {
    const refetchOnFocus = () => {
      if (document.visibilityState === 'visible') void query.refetch({ cancelRefetch: false })
    }
    window.addEventListener('focus', refetchOnFocus)
    return () => window.removeEventListener('focus', refetchOnFocus)
  }, [query.refetch])

  return query
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

  return useMutation({
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

function replaceSeasonSummary(
  current: WorkspaceProjection | undefined,
  season: SeasonSummary,
) {
  if (!current) return current
  const seasons = current.seasons?.length ? current.seasons : [current.season]
  const exists = seasons.some((candidate) => candidate.id === season.id)
  return {
    ...current,
    season: current.season.id === season.id ? season : current.season,
    seasons: exists
      ? seasons.map((candidate) => candidate.id === season.id ? season : candidate)
      : [...seasons, season],
  }
}

export function useUpdateSeasonMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: (request: UpdateSeasonRequest) => updateSeason(scope, request),
    onSuccess: (season) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceSeasonSummary(current, season))
    },
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateRoundScheduleMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: (request: UpdateRoundScheduleRequest) =>
      updateRoundSchedule(scope, request),
    onSuccess: (season) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceSeasonSummary(current, season))
    },
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateSeasonEndingMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: (request: UpdateSeasonEndingRequest) =>
      updateSeasonEnding(scope, request),
    onSuccess: (season) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceSeasonSummary(current, season))
    },
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useCreateNextSeasonMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: IdempotentCreateCommand<CreateNextSeasonRequest>) =>
      createNextSeason(scope, request, idempotencyKey),
    onSuccess: (result: CreateNextSeasonResponse) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) => {
        const withSource = replaceSeasonSummary(current, result.sourceSeason)
        return replaceSeasonSummary(withSource, result.season)
      })
    },
    onSettled: invalidateTeam,
  })
}

export function useCreateRoleMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoleRequest>) =>
      createRole(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useCreateMemberMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateMemberRequest>) =>
      createMember(scope, request, idempotencyKey),
    onSuccess: (createdMember) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) => {
        if (!current) return current
        const alreadyCreated = current.members.some((member) => member.id === createdMember.id)
        return {
          ...current,
          members: alreadyCreated
            ? current.members.map((member) =>
                member.id === createdMember.id ? createdMember : member)
            : [...current.members, createdMember],
        }
      })
    },
    onSettled: invalidateTeam,
  })
}

function replaceMemberInWorkspace(
  current: WorkspaceProjection | undefined,
  updatedMember: WorkspaceProjection['members'][number],
) {
  if (!current) return current
  return {
    ...current,
    members: current.members.map((member) =>
      member.id === updatedMember.id ? updatedMember : member),
    decisions: current.decisions.map((decision) =>
      decision.authorMemberId === updatedMember.id
        ? { ...decision, authorName: updatedMember.name }
        : decision),
  }
}

export function useUpdateMemberMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateMemberRequest>) =>
      updateMember(scope, id, request),
    onSuccess: (updatedMember) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceMemberInWorkspace(current, updatedMember))
    },
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateMemberDeactivationMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidateTeam } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateMemberDeactivationRequest>) =>
      updateMemberDeactivation(scope, id, request),
    onSuccess: (updatedMember) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceMemberInWorkspace(current, updatedMember))
    },
    onSettled: invalidateUnlessContentConflict(invalidateTeam),
  })
}

export function useUpdateRoleMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateRoleRequest>) =>
      updateRole(scope, id, request),
    onSuccess: (updatedRole) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              roles: current.roles.map((role) =>
                role.id === updatedRole.id ? updatedRole : role,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

function replaceRoleHandoffTransition(
  current: WorkspaceProjection | undefined,
  result: RoleHandoffTransitionResponse,
) {
  if (!current) return current
  const exists = current.roleHandoffs.some((handoff) => handoff.id === result.handoff.id)
  return {
    ...current,
    roles: current.roles.map((role) =>
      role.id === result.role.id ? { ...role, ...result.role } : role),
    roleHandoffs: exists
      ? current.roleHandoffs.map((handoff) =>
          handoff.id === result.handoff.id ? result.handoff : handoff)
      : [result.handoff, ...current.roleHandoffs],
  }
}

export function usePrepareRoleHandoffMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: IdempotentCreateCommand<PrepareRoleHandoffCommandRequest>) => {
      const { roleId, ...body } = request
      return prepareRoleHandoff(scope, roleId, body, idempotencyKey)
    },
    onSuccess: (result) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceRoleHandoffTransition(current, result))
    },
    onSettled: invalidate,
  })
}

export function useTransferRoleHandoffMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({
      roleId,
      handoffId,
      request,
    }: RoleHandoffTransitionCommand<TransferRoleHandoffRequest>) =>
      transferRoleHandoff(scope, roleId, handoffId, request),
    onSuccess: (result) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceRoleHandoffTransition(current, result))
    },
    onSettled: invalidate,
  })
}

export function useAcceptRoleHandoffMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({
      roleId,
      handoffId,
      request,
    }: RoleHandoffTransitionCommand<ConfirmRoleHandoffRequest>) =>
      acceptRoleHandoff(scope, roleId, handoffId, request),
    onSuccess: (result) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceRoleHandoffTransition(current, result))
    },
    onSettled: invalidate,
  })
}

export function useCancelRoleHandoffMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({
      roleId,
      handoffId,
      request,
    }: RoleHandoffTransitionCommand<CancelRoleHandoffRequest>) =>
      cancelRoleHandoff(scope, roleId, handoffId, request),
    onSuccess: (result) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        replaceRoleHandoffTransition(current, result))
    },
    onSettled: invalidate,
  })
}

export function useCreateRoutineMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoutineRequest>) =>
      createRoutine(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateRoutineMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateRoutineRequest>) =>
      updateRoutine(scope, id, request),
    onSuccess: (updatedRoutine) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              routines: current.routines.map((routine) =>
                routine.id === updatedRoutine.id ? updatedRoutine : routine,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateSeasonRoundMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateSeasonRoundRequest>) =>
      createSeasonRound(scope, request, idempotencyKey),
    onSuccess: (createdRound) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) => {
        if (!current) return current
        const alreadyCreated = current.rounds.some((round) => round.id === createdRound.id)
        return {
          ...current,
          rounds: alreadyCreated
            ? current.rounds.map((round) => round.id === createdRound.id ? createdRound : round)
            : [...current.rounds, createdRound],
        }
      })
    },
    onSettled: invalidate,
  })
}

export function useUpdateSeasonRoundMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateSeasonRoundRequest>) =>
      updateSeasonRound(scope, id, request),
    onSuccess: (updatedRound) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              rounds: current.rounds.map((round) =>
                round.id === updatedRound.id ? updatedRound : round,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useSeasonRoundArchiveMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, archived }: ArchiveCommand) =>
      setSeasonRoundArchived(scope, id, archived),
    onSuccess: (updatedRound) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              rounds: current.rounds.map((round) =>
                round.id === updatedRound.id ? updatedRound : round,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useRoutineExecutionCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
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
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateDecisionRequest>) =>
      createDecision(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateDecisionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateDecisionRequest>) =>
      updateDecision(scope, id, request),
    onSuccess: (updatedDecision) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              decisions: current.decisions.map((decision) =>
                decision.id === updatedDecision.id ? updatedDecision : decision,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useDecisionArchiveMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, archived }: ArchiveCommand) => setDecisionArchived(scope, id, archived),
    onSuccess: (updatedDecision) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              decisions: current.decisions.map((decision) =>
                decision.id === updatedDecision.id ? updatedDecision : decision,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateHandoffItemMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateHandoffItemRequest>) =>
      createHandoffItem(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateHandoffItemMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateHandoffItemRequest>) =>
      updateHandoffItem(scope, id, request),
    onSuccess: (updatedItem) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              handoffItems: current.handoffItems.map((item) =>
                item.id === updatedItem.id ? updatedItem : item,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useHandoffCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
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
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, archived }: ArchiveCommand) =>
      setHandoffItemArchived(scope, id, archived),
    onSuccess: (updatedItem) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              handoffItems: current.handoffItems.map((item) =>
                item.id === updatedItem.id ? updatedItem : item,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}

export function useCreateRoleResourceMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateRoleResourceRequest>) =>
      createRoleResource(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useUpdateRoleResourceMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, request }: UpdateCommand<UpdateRoleResourceRequest>) =>
      updateRoleResource(scope, id, request),
    onSuccess: (updatedResource) => {
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              resources: current.resources.map((resource) =>
                resource.id === updatedResource.id ? updatedResource : resource,
              ),
            }
          : current,
      )
    },
    onSettled: invalidateUnlessContentConflict(invalidate),
  })
}
