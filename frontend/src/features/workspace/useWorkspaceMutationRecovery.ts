import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'
import { isWorkspaceMutationForScope } from './queries'

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
  const callbacksRef = useRef({
    onRoleHandoffConflict,
    onWorkspaceContentConflict,
    onSeasonEnded,
  })
  callbacksRef.current = {
    onRoleHandoffConflict,
    onWorkspaceContentConflict,
    onSeasonEnded,
  }

  useEffect(() => {
    let handledRoleHandoffConflict: unknown = null
    let handledWorkspaceContentConflict: unknown = null
    let handledSeasonEnded: unknown = null

    return queryClient.getMutationCache().subscribe((event) => {
      if (event.type !== 'updated' || event.action.type !== 'error') return
      if (!isWorkspaceMutationForScope(event.mutation.options.mutationKey, {
        teamId,
        seasonId,
      })) return

      const error = event.action.error
      if (!(error instanceof ApiError)) return
      if (error.code === 'ROLE_HANDOFF_STATE_CONFLICT') {
        if (handledRoleHandoffConflict === error) return
        handledRoleHandoffConflict = error
        callbacksRef.current.onRoleHandoffConflict()
        return
      }
      if (error.code === 'WORKSPACE_CONTENT_CONFLICT') {
        if (handledWorkspaceContentConflict === error) return
        handledWorkspaceContentConflict = error
        callbacksRef.current.onWorkspaceContentConflict()
        return
      }
      if (error.code !== 'SEASON_ENDED' || handledSeasonEnded === error) return

      handledSeasonEnded = error
      callbacksRef.current.onSeasonEnded()
    })
  }, [queryClient, seasonId, teamId])
}
