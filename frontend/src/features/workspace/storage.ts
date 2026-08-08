import type { WorkspaceProjection } from './types'

const RECENT_WORKSPACES_STORAGE_KEY = 'baton-recent-workspaces:v1'
const MAX_RECENT_WORKSPACES = 5
const EMPTY_RECENT_WORKSPACES: RecentWorkspace[] = []

type RecentWorkspacesListener = () => void

const recentWorkspacesListeners = new Set<RecentWorkspacesListener>()
let cachedStorageValue: string | null = null
let cachedSnapshot: RecentWorkspace[] = EMPTY_RECENT_WORKSPACES
let snapshotInitialized = false

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

function normalizeRecentWorkspaces(value: unknown): RecentWorkspace[] {
  if (!Array.isArray(value)) return EMPTY_RECENT_WORKSPACES

  const identities = new Set<string>()
  const normalized = value
    .filter(isRecentWorkspace)
    .sort((left, right) => Date.parse(right.lastOpenedAt) - Date.parse(left.lastOpenedAt))
    .filter((workspace) => {
      const identity = workspaceIdentity(workspace)
      if (identities.has(identity)) return false
      identities.add(identity)
      return true
    })
    .slice(0, MAX_RECENT_WORKSPACES)
  return normalized.length > 0 ? normalized : EMPTY_RECENT_WORKSPACES
}

function parseRecentWorkspaces(storageValue: string | null) {
  if (storageValue === null) return EMPTY_RECENT_WORKSPACES
  try {
    return normalizeRecentWorkspaces(JSON.parse(storageValue) as unknown)
  } catch {
    return EMPTY_RECENT_WORKSPACES
  }
}

function notifyRecentWorkspacesChange() {
  recentWorkspacesListeners.forEach((listener) => listener())
}

function writeRecentWorkspaces(workspaces: readonly RecentWorkspace[]) {
  try {
    const serialized = JSON.stringify(workspaces)
    window.localStorage.setItem(RECENT_WORKSPACES_STORAGE_KEY, serialized)
    if (window.localStorage.getItem(RECENT_WORKSPACES_STORAGE_KEY) !== serialized) return false
    notifyRecentWorkspacesChange()
    return true
  } catch {
    return false
  }
}

export function readRecentWorkspaces(): RecentWorkspace[] {
  let storageValue: string | null
  try {
    storageValue = window.localStorage.getItem(RECENT_WORKSPACES_STORAGE_KEY)
  } catch {
    return snapshotInitialized ? cachedSnapshot : EMPTY_RECENT_WORKSPACES
  }

  if (snapshotInitialized && storageValue === cachedStorageValue) return cachedSnapshot

  cachedStorageValue = storageValue
  cachedSnapshot = parseRecentWorkspaces(storageValue)
  snapshotInitialized = true
  return cachedSnapshot
}

export function readRecentWorkspacesServerSnapshot() {
  return EMPTY_RECENT_WORKSPACES
}

export function repairRecentWorkspaces() {
  let storageValue: string | null
  try {
    storageValue = window.localStorage.getItem(RECENT_WORKSPACES_STORAGE_KEY)
  } catch {
    return false
  }
  if (storageValue === null) return true

  const workspaces = parseRecentWorkspaces(storageValue)
  if (storageValue === JSON.stringify(workspaces)) return true
  return writeRecentWorkspaces(workspaces)
}

export function subscribeRecentWorkspaces(onChange: () => void) {
  const handleStorage = (event: StorageEvent) => {
    if (event.key === null || event.key === RECENT_WORKSPACES_STORAGE_KEY) {
      onChange()
    }
  }
  recentWorkspacesListeners.add(onChange)
  window.addEventListener('storage', handleStorage)
  return () => {
    recentWorkspacesListeners.delete(onChange)
    window.removeEventListener('storage', handleStorage)
  }
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
