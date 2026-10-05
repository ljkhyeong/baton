import { useEffect, useEffectEvent } from 'react'
import { matchMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { workspaceMutationKey } from './queries'

type WorkspaceMutationRecoveryOptions = {
  teamId: string
  seasonId: string
  onRoleHandoffConflict: () => void
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
  const recoverMutation = useEffectEvent((error: ApiError) => {
    if (error.code === 'ROLE_HANDOFF_STATE_CONFLICT') {
      onRoleHandoffConflict()
      return
    }
    if (error.code === 'WORKSPACE_CONTENT_CONFLICT') {
      onWorkspaceContentConflict()
      return
    }
    if (error.code === 'SEASON_ENDED') {
      onSeasonEnded()
    }
  })

  useEffect(() => {
    return queryClient.getMutationCache().subscribe((event) => {
      if (event.type !== 'updated' || event.action.type !== 'error') return
      if (!matchMutation({ mutationKey: workspaceMutationKey({ teamId, seasonId }), exact: true }, event.mutation)) return

      const error = event.action.error
      if (!(error instanceof ApiError)) return
      recoverMutation(error)
    })
  }, [queryClient, seasonId, teamId])
}
