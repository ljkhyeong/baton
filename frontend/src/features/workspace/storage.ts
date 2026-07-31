import type { WorkspaceProjection } from './types'
import {
  discardUnscopedRecentWorkspaces,
  recentWorkspacesStorageKey,
} from '@/shared/auth/accountScopedState'

const MAX_RECENT_WORKSPACES = 5

export type RecentWorkspace = {
  teamId: string
  seasonId: string
  teamName: string
  seasonName: string
  lastOpenedAt: string
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isRecentWorkspace(value: unknown): value is RecentWorkspace {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<RecentWorkspace>
  return isNonEmptyString(candidate.teamId)
    && isNonEmptyString(candidate.seasonId)
    && isNonEmptyString(candidate.teamName)
    && isNonEmptyString(candidate.seasonName)
    && isNonEmptyString(candidate.lastOpenedAt)
    && !Number.isNaN(Date.parse(candidate.lastOpenedAt))
}

function workspaceIdentity(workspace: Pick<RecentWorkspace, 'teamId' | 'seasonId'>) {
  return `${workspace.teamId}:${workspace.seasonId}`
}

function writeRecentWorkspaces(accountId: string, workspaces: RecentWorkspace[]) {
  if (!accountId) return false
  try {
    window.localStorage.setItem(
      recentWorkspacesStorageKey(accountId),
      JSON.stringify(workspaces),
    )
    return true
  } catch {
    return false
  }
}

export function readRecentWorkspaces(accountId: string): RecentWorkspace[] {
  discardUnscopedRecentWorkspaces()
  if (!accountId) return []
  const storageKey = recentWorkspacesStorageKey(accountId)
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (!raw) return []

    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      window.localStorage.removeItem(storageKey)
      return []
    }

    const identities = new Set<string>()
    const normalized = parsed
      .filter(isRecentWorkspace)
      .sort((left, right) => Date.parse(right.lastOpenedAt) - Date.parse(left.lastOpenedAt))
      .filter((workspace) => {
        const identity = workspaceIdentity(workspace)
        if (identities.has(identity)) return false
        identities.add(identity)
        return true
      })
      .slice(0, MAX_RECENT_WORKSPACES)

    if (JSON.stringify(parsed) !== JSON.stringify(normalized)) {
      writeRecentWorkspaces(accountId, normalized)
    }
    return normalized
  } catch {
    try {
      window.localStorage.removeItem(storageKey)
    } catch {
      // Storage can be unavailable in privacy modes. The workspace still remains usable through its share link.
    }
    return []
  }
}

export function subscribeRecentWorkspaces(accountId: string, onChange: () => void) {
  const storageKey = recentWorkspacesStorageKey(accountId)
  const handleStorage = (event: StorageEvent) => {
    if (event.key === null || event.key === storageKey) {
      onChange()
    }
  }
  window.addEventListener('storage', handleStorage)
  return () => window.removeEventListener('storage', handleStorage)
}

export function rememberRecentWorkspace(
  accountId: string,
  workspace: WorkspaceProjection,
) {
  if (!accountId) return false
  const recent: RecentWorkspace = {
    teamId: workspace.team.id,
    seasonId: workspace.season.id,
    teamName: workspace.team.name,
    seasonName: workspace.season.name,
    lastOpenedAt: new Date().toISOString(),
  }
  const identity = workspaceIdentity(recent)
  const next = [
    recent,
    ...readRecentWorkspaces(accountId)
      .filter((candidate) => workspaceIdentity(candidate) !== identity),
  ].slice(0, MAX_RECENT_WORKSPACES)
  return writeRecentWorkspaces(accountId, next)
}

export function forgetRecentWorkspace(
  accountId: string,
  teamId: string,
  seasonId: string,
) {
  if (!accountId) return false
  const identity = workspaceIdentity({ teamId, seasonId })
  return writeRecentWorkspaces(
    accountId,
    readRecentWorkspaces(accountId)
      .filter((workspace) => workspaceIdentity(workspace) !== identity),
  )
}
