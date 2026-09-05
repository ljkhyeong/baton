import type { WorkspaceProjection } from './types'

export function personalWork(workspace: WorkspaceProjection, memberId: string) {
  const myRoleIds = new Set(workspace.roles.filter(role => role.currentMemberId === memberId).map(role => role.id))
  const unfinished = workspace.rounds.filter(round => !round.archivedAt).flatMap(round => (
    round.routineExecutions.filter(execution => execution.status !== 'DONE' && myRoleIds.has(execution.ownerRoleId))
      .map(execution => ({ round, execution }))
  )).sort((left, right) => (left.execution.deadlineAt ?? 'z').localeCompare(right.execution.deadlineAt ?? 'z'))
  const awaiting = workspace.roleHandoffs.filter(handoff => handoff.status === 'TRANSFERRED' && handoff.toMemberId === memberId)
  return { unfinished, awaiting }
}
