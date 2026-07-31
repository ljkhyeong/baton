import type { QueryClient } from '@tanstack/react-query'

const WORKSPACE_QUERY_ROOT = 'teams'
const LEGACY_ACCESS_KEY_PREFIX = 'baton-access-key:'
const ROUND_ENTRY_CONTEXT_PREFIX = 'baton-round-entry:v1:'
const LEGACY_RECENT_WORKSPACES_STORAGE_KEY = 'baton-recent-workspaces:v1'
const RECENT_WORKSPACES_STORAGE_PREFIX = 'baton-recent-workspaces:v2:'

function removeStorageEntries(storage: Storage, prefix: string) {
  const keys: string[] = []
  for (let index = 0; index < storage.length; index += 1) {
    const key = storage.key(index)
    if (key?.startsWith(prefix)) keys.push(key)
  }
  keys.forEach((key) => storage.removeItem(key))
  return keys.every((key) => storage.getItem(key) === null)
}

export function discardPersistedWorkspaceAccessKeys() {
  try {
    removeStorageEntries(window.localStorage, LEGACY_ACCESS_KEY_PREFIX)
  } catch {
    // The current page can continue with its in-memory or fragment credential.
  }
}

export function recentWorkspacesStorageKey(accountId: string) {
  return `${RECENT_WORKSPACES_STORAGE_PREFIX}${encodeURIComponent(accountId)}`
}

export function discardUnscopedRecentWorkspaces() {
  try {
    window.localStorage.removeItem(LEGACY_RECENT_WORKSPACES_STORAGE_KEY)
  } catch {
    // A blocked local storage cannot expose the legacy list to this page.
  }
}

function clearRecentWorkspaceMetadata(accountId?: string) {
  try {
    window.localStorage.removeItem(LEGACY_RECENT_WORKSPACES_STORAGE_KEY)
    if (accountId) {
      window.localStorage.removeItem(recentWorkspacesStorageKey(accountId))
    }
  } catch {
    // Account cleanup remains best-effort when browser storage is unavailable.
  }
}

export function consumePersistedWorkspaceAccessKey(teamId: string) {
  try {
    const accessKey =
      window.localStorage.getItem(`${LEGACY_ACCESS_KEY_PREFIX}${teamId}`) ?? ''
    if (!removeStorageEntries(window.localStorage, LEGACY_ACCESS_KEY_PREFIX)) return ''
    return accessKey
  } catch {
    // Never use a bearer credential that could not be verified as removed.
    return ''
  }
}

export function clearRoundEntryContexts() {
  try {
    removeStorageEntries(window.sessionStorage, ROUND_ENTRY_CONTEXT_PREFIX)
  } catch {
    // A blocked session storage has no reusable context to clear.
  }
}

export async function clearAccountScopedClientState(
  queryClient: QueryClient,
  accountId?: string,
) {
  await queryClient.cancelQueries({
    predicate: (query) => query.queryKey[0] === WORKSPACE_QUERY_ROOT,
  })
  queryClient.removeQueries({
    predicate: (query) => query.queryKey[0] === WORKSPACE_QUERY_ROOT,
  })
  queryClient.getMutationCache()
    .findAll({
      predicate: (mutation) =>
        mutation.options.mutationKey?.[0] === 'workspace-mutations',
    })
    .forEach((mutation) => queryClient.getMutationCache().remove(mutation))
  clearRoundEntryContexts()
  discardPersistedWorkspaceAccessKeys()
  clearRecentWorkspaceMetadata(accountId)
}
