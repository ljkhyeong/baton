import {
  clearAllRoundRoomEntryContexts,
  forgetRoundRoomEntryContextsForTeam,
} from '@/features/round/storage'
import {
  clearAllWorkspaceCapabilitiesAndRecents,
  forgetWorkspaceCapabilityAndRecents,
} from '@/features/workspace/storage'
import type { ForgetWorkspaceCapabilityResult } from '@/features/workspace/storage'

export type ForgetWorkspaceDeviceStateResult =
  | ForgetWorkspaceCapabilityResult
  | 'round-context-cleanup-failed'

export function forgetWorkspaceDeviceState(
  teamId: string,
): ForgetWorkspaceDeviceStateResult {
  const capabilityResult = forgetWorkspaceCapabilityAndRecents(teamId)
  const roundContextRemoved = forgetRoundRoomEntryContextsForTeam(teamId)
  if (capabilityResult !== 'removed') return capabilityResult
  return roundContextRemoved ? 'removed' : 'round-context-cleanup-failed'
}

export function clearAllWorkspaceDeviceState() {
  const capabilitiesRemoved = clearAllWorkspaceCapabilitiesAndRecents()
  const roundContextsRemoved = clearAllRoundRoomEntryContexts()
  return capabilitiesRemoved && roundContextsRemoved
}
