import { useEffect, useEffectEvent } from 'react'
import { matchMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { workspaceMutationKey } from './queries'

type WorkspaceMutationRecoveryOptions = {
  teamId: string
  seasonId: string
  onRoleHandoffConflict: (message: string) => void
  onWorkspaceContentConflict: () => void
  onSeasonEnded: () => void
}

export function useWorkspaceMutationRecovery({
  teamId,
  seasonId,
  onRoleHandoffConflict,
  onWorkspaceContentConflict,
  onSeasonEnded,
}: WorkspaceMutationRecoveryOptions) {
  const queryClient = useQueryClient()
  const recoverMutation = useEffectEvent((error: ApiError, conflictShownInForm: boolean) => {
    if (error.code === 'SEASON_ENDED') {
      onSeasonEnded()
      return
    }
    if (conflictShownInForm) return
    if (error.code === 'ROLE_HANDOFF_STATE_CONFLICT') {
      onRoleHandoffConflict(error.message)
      return
    }
    if (error.code === 'WORKSPACE_CONTENT_CONFLICT') {
      onWorkspaceContentConflict()
    }
  })

  useEffect(() => {
    return queryClient.getMutationCache().subscribe((event) => {
      if (event.type !== 'updated' || event.action.type !== 'error') return
      if (!matchMutation({ mutationKey: workspaceMutationKey({ teamId, seasonId }), exact: true }, event.mutation)) return

      const error = event.action.error
      if (!(error instanceof ApiError)) return
      recoverMutation(error, event.mutation.meta?.showConflictInForm === true)
    })
  }, [queryClient, seasonId, teamId])
}
