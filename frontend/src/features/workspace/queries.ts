import { useEffect } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import {
  createDecision,
  createHandoffItem,
  createRole,
  createRoleResource,
  createRoutine,
  createSeasonRound,
  getWorkspace,
  rotateAccessKey,
  setHandoffItemCompletion,
  setRoutineExecutionCompletion,
  updateRole,
  updateRoleResource,
  updateRoutine,
} from './api'
import type { WorkspaceScope } from './api'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoleResourceRequest,
  CreateRoutineRequest,
  CreateSeasonRoundRequest,
  UpdateRoleRequest,
  UpdateRoleResourceRequest,
  UpdateRoutineRequest,
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

export const workspaceKeys = {
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
  }
}

export function useRotateAccessKeyMutation(scope: WorkspaceScope) {
  return useMutation({
    mutationFn: (idempotencyKey: string) => rotateAccessKey(scope, idempotencyKey),
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
    onSettled: invalidate,
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
      const previous = queryClient.getQueryData<WorkspaceProjection>(queryKey)
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
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(queryKey, context.previous)
    },
    onSettled: invalidate,
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

export function useCreateHandoffItemMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ request, idempotencyKey }: IdempotentCreateCommand<CreateHandoffItemRequest>) =>
      createHandoffItem(scope, request, idempotencyKey),
    onSettled: invalidate,
  })
}

export function useHandoffCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, completed }: { id: string; completed: boolean }) =>
      setHandoffItemCompletion(scope, id, completed),
    onMutate: async ({ id, completed }) => {
      await queryClient.cancelQueries({ queryKey })
      const previous = queryClient.getQueryData<WorkspaceProjection>(queryKey)
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
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(queryKey, context.previous)
    },
    onSettled: invalidate,
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
    onSettled: invalidate,
  })
}
