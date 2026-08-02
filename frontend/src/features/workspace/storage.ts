import type { WorkspaceProjection } from './types'

const RECENT_WORKSPACES_STORAGE_KEY = 'baton-recent-workspaces:v1'
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

function writeRecentWorkspaces(workspaces: RecentWorkspace[]) {
  try {
    window.localStorage.setItem(RECENT_WORKSPACES_STORAGE_KEY, JSON.stringify(workspaces))
    return true
  } catch {
    return false
  }
}

export function readRecentWorkspaces(): RecentWorkspace[] {
  try {
    const raw = window.localStorage.getItem(RECENT_WORKSPACES_STORAGE_KEY)
    if (!raw) return []

    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) {
      window.localStorage.removeItem(RECENT_WORKSPACES_STORAGE_KEY)
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

    if (JSON.stringify(parsed) !== JSON.stringify(normalized)) writeRecentWorkspaces(normalized)
    return normalized
  } catch {
    try {
      window.localStorage.removeItem(RECENT_WORKSPACES_STORAGE_KEY)
    } catch {
      // Storage can be unavailable in privacy modes. The workspace still remains usable through its share link.
    }
    return []
  }
}

export function subscribeRecentWorkspaces(onChange: () => void) {
  const handleStorage = (event: StorageEvent) => {
    if (event.key === null || event.key === RECENT_WORKSPACES_STORAGE_KEY) {
      onChange()
    }
  }
  window.addEventListener('storage', handleStorage)
  return () => window.removeEventListener('storage', handleStorage)
}

export function rememberRecentWorkspace(workspace: WorkspaceProjection) {
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
    ...readRecentWorkspaces().filter((candidate) => workspaceIdentity(candidate) !== identity),
  ].slice(0, MAX_RECENT_WORKSPACES)
  return writeRecentWorkspaces(next)
}

export function forgetRecentWorkspace(teamId: string, seasonId: string) {
  const identity = workspaceIdentity({ teamId, seasonId })
  return writeRecentWorkspaces(
    readRecentWorkspaces().filter((workspace) => workspaceIdentity(workspace) !== identity),
  )
}
