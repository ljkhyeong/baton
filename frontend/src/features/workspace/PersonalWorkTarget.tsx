import { useEffect, useRef } from 'react'
import { useSearchParams } from 'react-router-dom'
import type { WorkspaceProjection } from './types'

export function PersonalWorkTarget({ workspace, onOpenRound, onOpenHandoff }: {
  workspace: WorkspaceProjection; onOpenRound: (roundId: string, executionId: string) => void
  onOpenHandoff: (roleId: string) => void
}) {
  const [params] = useSearchParams()
  const handled = useRef('')
  useEffect(() => {
    const target = JSON.stringify([workspace.team.id, workspace.season.id, params.toString()])
    if (handled.current === target) return
    handled.current = target
    if (params.get('workKind') === 'execution') {
      const round = workspace.rounds.find(value => value.id === params.get('roundId') && !value.archivedAt)
      const execution = round?.routineExecutions.find(value => value.id === params.get('workId'))
      if (round && execution) onOpenRound(round.id, execution.id)
    } else if (params.get('workKind') === 'handoff') {
      const handoff = workspace.roleHandoffs.find(value => value.id === params.get('workId'))
      if (handoff) onOpenHandoff(handoff.roleId)
    }
  }, [params, workspace, onOpenRound, onOpenHandoff])
  return null
}
