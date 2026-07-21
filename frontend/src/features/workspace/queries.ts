import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createDecision,
  createHandoffItem,
  createRole,
  createRoutine,
  getWorkspace,
  rotateAccessKey,
  setHandoffItemCompletion,
  setRoutineCompletion,
} from './api'
import type { WorkspaceScope } from './api'
import type {
  CreateDecisionRequest,
  CreateHandoffItemRequest,
  CreateRoleRequest,
  CreateRoutineRequest,
  WorkspaceProjection,
} from './types'

export const workspaceKeys = {
  detail: (teamId: string, seasonId: string, accessKey: string) =>
    ['teams', teamId, 'seasons', seasonId, 'workspace', { accessKey }] as const,
}

export function useWorkspaceQuery(scope: WorkspaceScope) {
  return useQuery({
    queryKey: workspaceKeys.detail(scope.teamId, scope.seasonId, scope.accessKey),
    queryFn: () => getWorkspace(scope),
    enabled: Boolean(scope.teamId && scope.seasonId && scope.accessKey),
    refetchOnWindowFocus: true,
  })
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
    mutationFn: (request: CreateRoleRequest) => createRole(scope, request),
    onSuccess: invalidate,
  })
}

export function useCreateRoutineMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: (request: CreateRoutineRequest) => createRoutine(scope, request),
    onSuccess: invalidate,
  })
}

export function useRoutineCompletionMutation(scope: WorkspaceScope) {
  const { queryClient, queryKey, invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: ({ id, completed }: { id: string; completed: boolean }) =>
      setRoutineCompletion(scope, id, completed),
    onMutate: async ({ id, completed }) => {
      await queryClient.cancelQueries({ queryKey })
      const previous = queryClient.getQueryData<WorkspaceProjection>(queryKey)
      queryClient.setQueryData<WorkspaceProjection>(queryKey, (current) =>
        current
          ? {
              ...current,
              routines: current.routines.map((routine) =>
                routine.id === id ? { ...routine, status: completed ? 'DONE' : 'WAITING' } : routine,
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
    mutationFn: (request: CreateDecisionRequest) => createDecision(scope, request),
    onSuccess: invalidate,
  })
}

export function useCreateHandoffItemMutation(scope: WorkspaceScope) {
  const { invalidate } = useInvalidateWorkspace(scope)
  return useMutation({
    mutationFn: (request: CreateHandoffItemRequest) => createHandoffItem(scope, request),
    onSuccess: invalidate,
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
