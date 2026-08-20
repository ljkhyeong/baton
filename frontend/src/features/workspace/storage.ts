import { isInstant, isNonEmptyString } from '@/shared/api/responseValidation'
import type { WorkspaceProjection } from './types'

const RECENT_WORKSPACES_STORAGE_KEY = 'baton-recent-workspaces:v1'
const ACCESS_KEY_STORAGE_PREFIX = 'baton-access-key:'
const MAX_RECENT_WORKSPACES = 5
const EMPTY_RECENT_WORKSPACES: RecentWorkspace[] = []

type RecentWorkspacesListener = () => void
type WorkspaceCapabilityListener = () => void

type WorkspaceCapabilitySnapshot = Readonly<{
  accessKey: string
  removalRevision: number
}>

const recentWorkspacesListeners = new Set<RecentWorkspacesListener>()
const workspaceCapabilityListeners = new Map<string, Set<WorkspaceCapabilityListener>>()
const workspaceCapabilityRemovalRevisions = new Map<string, number>()
const workspaceCapabilitySnapshots = new Map<string, WorkspaceCapabilitySnapshot>()
const EMPTY_WORKSPACE_CAPABILITY_SNAPSHOT: WorkspaceCapabilitySnapshot = {
  accessKey: '',
  removalRevision: 0,
}
let cachedStorageValue: string | null = null
let cachedSnapshot: RecentWorkspace[] = EMPTY_RECENT_WORKSPACES
let snapshotInitialized = false
let capabilityStorageListenerInstalled = false

export type RecentWorkspace = {
  teamId: string
  seasonId: string
  teamName: string
  seasonName: string
  lastOpenedAt: string
}

export type ForgetWorkspaceCapabilityResult =
  | 'removed'
  | 'capability-removal-failed'
  | 'recent-list-update-failed'

const accessKeyStorageKey = (teamId: string) =>
  `${ACCESS_KEY_STORAGE_PREFIX}${teamId}`

function readStoredAccessKey(teamId: string) {
  try {
    return window.localStorage.getItem(accessKeyStorageKey(teamId)) ?? ''
  } catch {
    return ''
  }
}

function notifyWorkspaceCapabilityChange(teamId: string) {
  workspaceCapabilityListeners.get(teamId)?.forEach((listener) => listener())
}

function recordWorkspaceCapabilityRemoval(teamId: string) {
  workspaceCapabilityRemovalRevisions.set(
    teamId,
    (workspaceCapabilityRemovalRevisions.get(teamId) ?? 0) + 1,
  )
  workspaceCapabilitySnapshots.delete(teamId)
}

function handleWorkspaceCapabilityStorage(event: StorageEvent) {
  if (event.key === null) {
    workspaceCapabilityListeners.forEach((_listeners, teamId) => {
      recordWorkspaceCapabilityRemoval(teamId)
      notifyWorkspaceCapabilityChange(teamId)
    })
    return
  }
  if (!event.key.startsWith(ACCESS_KEY_STORAGE_PREFIX)) return

  const teamId = event.key.slice(ACCESS_KEY_STORAGE_PREFIX.length)
  if (!teamId) return
  if (event.newValue === null) recordWorkspaceCapabilityRemoval(teamId)
  else workspaceCapabilitySnapshots.delete(teamId)
  notifyWorkspaceCapabilityChange(teamId)
}

function synchronizeWorkspaceCapabilityStorageListener() {
  const shouldInstall = workspaceCapabilityListeners.size > 0
  if (shouldInstall === capabilityStorageListenerInstalled) return

  if (shouldInstall) {
    window.addEventListener('storage', handleWorkspaceCapabilityStorage)
  } else {
    window.removeEventListener('storage', handleWorkspaceCapabilityStorage)
  }
  capabilityStorageListenerInstalled = shouldInstall
}

export function saveAccessKey(teamId: string, accessKey: string) {
  try {
    window.localStorage.setItem(accessKeyStorageKey(teamId), accessKey)
    workspaceCapabilitySnapshots.delete(teamId)
    return true
  } catch {
    return false
  }
}

export function readAccessKey(teamId: string) {
  return readStoredAccessKey(teamId)
}

export function readWorkspaceCapabilitySnapshot(teamId: string) {
  const accessKey = readStoredAccessKey(teamId)
  const removalRevision = workspaceCapabilityRemovalRevisions.get(teamId) ?? 0
  const current = workspaceCapabilitySnapshots.get(teamId)
  if (current?.accessKey === accessKey && current.removalRevision === removalRevision) {
    return current
  }

  const next = { accessKey, removalRevision }
  workspaceCapabilitySnapshots.set(teamId, next)
  return next
}

export function readWorkspaceCapabilityServerSnapshot() {
  return EMPTY_WORKSPACE_CAPABILITY_SNAPSHOT
}

export function subscribeWorkspaceCapability(
  teamId: string,
  onChange: WorkspaceCapabilityListener,
) {
  const listeners = workspaceCapabilityListeners.get(teamId)
    ?? new Set<WorkspaceCapabilityListener>()
  listeners.add(onChange)
  workspaceCapabilityListeners.set(teamId, listeners)
  synchronizeWorkspaceCapabilityStorageListener()

  return () => {
    listeners.delete(onChange)
    if (listeners.size === 0) workspaceCapabilityListeners.delete(teamId)
    synchronizeWorkspaceCapabilityStorageListener()
  }
}

function isRecentWorkspace(value: unknown): value is RecentWorkspace {
  if (!value || typeof value !== 'object') return false
  const candidate = value as Partial<RecentWorkspace>
  return isNonEmptyString(candidate.teamId)
    && isNonEmptyString(candidate.seasonId)
    && isNonEmptyString(candidate.teamName)
    && isNonEmptyString(candidate.seasonName)
    && isInstant(candidate.lastOpenedAt)
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

export function forgetWorkspaceCapabilityAndRecents(
  teamId: string,
): ForgetWorkspaceCapabilityResult {
  try {
    const storageKey = accessKeyStorageKey(teamId)
    window.localStorage.removeItem(storageKey)
    recordWorkspaceCapabilityRemoval(teamId)
    notifyWorkspaceCapabilityChange(teamId)
  } catch {
    return 'capability-removal-failed'
  }

  return writeRecentWorkspaces(
    readRecentWorkspaces().filter((workspace) => workspace.teamId !== teamId),
  )
    ? 'removed'
    : 'recent-list-update-failed'
}

export function clearAllWorkspaceCapabilitiesAndRecents() {
  const affectedTeamIds = new Set(workspaceCapabilityListeners.keys())
  try {
    const storage = window.localStorage
    const keys = Array.from({ length: storage.length }, (_, index) => storage.key(index))
      .filter((key): key is string => key !== null)
      .filter((key) => key.startsWith(ACCESS_KEY_STORAGE_PREFIX))
    keys.forEach((key) => affectedTeamIds.add(key.slice(ACCESS_KEY_STORAGE_PREFIX.length)))
    keys.forEach((key) => storage.removeItem(key))
    storage.removeItem(RECENT_WORKSPACES_STORAGE_KEY)
    affectedTeamIds.forEach((teamId) => {
      recordWorkspaceCapabilityRemoval(teamId)
      notifyWorkspaceCapabilityChange(teamId)
    })
    notifyRecentWorkspacesChange()
    return true
  } catch {
    affectedTeamIds.forEach((teamId) => {
      recordWorkspaceCapabilityRemoval(teamId)
      notifyWorkspaceCapabilityChange(teamId)
    })
    notifyRecentWorkspacesChange()
    return false
  }
}
