import type {
  RoundRoomEntryContext,
  RoundRoomMapping,
  RoundRoomMappingScope,
} from '@/features/round/types'
import {
  isRoundRoomId,
  isSameUuid,
} from '@/shared/api/responseValidation'

const ENTRY_STORAGE_PREFIX = 'baton-round-entry:v1:'
const RESOURCE_STORAGE_PREFIX = 'baton-round-resource:v1:'

function resourceStorageKey(scope: RoundRoomMappingScope) {
  return `${RESOURCE_STORAGE_PREFIX}${scope.teamId}:${scope.seasonId}:${scope.resourceId}`
}

function entryStorageKey(roomId: string) {
  return `${ENTRY_STORAGE_PREFIX}${roomId}`
}

function isEntryContext(
  value: unknown,
  scope: RoundRoomMappingScope,
): value is RoundRoomEntryContext {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const candidate = value as Record<string, unknown>
  return candidate.version === 1
    && isSameUuid(candidate.teamId, scope.teamId)
    && isSameUuid(candidate.seasonId, scope.seasonId)
    && isSameUuid(candidate.resourceId, scope.resourceId)
    && isRoundRoomId(candidate.roomId)
}

function removeStoredContext(
  storage: Storage,
  scope: RoundRoomMappingScope,
  roomId: string,
) {
  storage.removeItem(resourceStorageKey(scope))
  storage.removeItem(entryStorageKey(roomId))
}

export function rememberRoundRoomMapping(
  scope: RoundRoomMappingScope,
  mapping: RoundRoomMapping,
) {
  const context: RoundRoomEntryContext = {
    version: 1,
    resourceId: mapping.resourceId,
    roomId: mapping.roomId,
    seasonId: mapping.seasonId,
    teamId: mapping.teamId,
  }
  const serialized = JSON.stringify(context)
  try {
    const storage = window.sessionStorage
    storage.setItem(entryStorageKey(mapping.roomId), serialized)
    storage.setItem(resourceStorageKey(scope), mapping.roomId)
    return true
  } catch {
    try {
      removeStoredContext(window.sessionStorage, scope, mapping.roomId)
    } catch {
      // 저장이 차단된 브라우저에서는 server mapping을 유지하고 입장만 중단한다.
    }
    return false
  }
}

export function readRoundRoomEntryContext(
  scope: RoundRoomMappingScope,
): RoundRoomEntryContext | null {
  try {
    const storage = window.sessionStorage
    const roomId = storage.getItem(resourceStorageKey(scope))
    if (!isRoundRoomId(roomId)) {
      if (roomId) storage.removeItem(resourceStorageKey(scope))
      return null
    }
    const serialized = storage.getItem(entryStorageKey(roomId))
    if (!serialized) {
      storage.removeItem(resourceStorageKey(scope))
      return null
    }
    const parsed: unknown = JSON.parse(serialized)
    if (!isEntryContext(parsed, scope) || parsed.roomId !== roomId) {
      removeStoredContext(storage, scope, roomId)
      return null
    }
    return {
      version: 1,
      resourceId: parsed.resourceId,
      roomId: parsed.roomId,
      seasonId: parsed.seasonId,
      teamId: parsed.teamId,
    }
  } catch {
    return null
  }
}

export function forgetRoundRoomEntryContext(
  scope: RoundRoomMappingScope,
  roomId: string,
) {
  try {
    const storage = window.sessionStorage
    removeStoredContext(storage, scope, roomId)
    return true
  } catch {
    return false
  }
}

function storageKeys(storage: Storage) {
  return Array.from({ length: storage.length }, (_, index) => storage.key(index))
    .filter((key): key is string => key !== null)
}

function removeItems(storage: Storage, keys: ReadonlySet<string>) {
  keys.forEach((key) => storage.removeItem(key))
  return true
}

export function forgetRoundRoomEntryContextsForTeam(teamId: string) {
  try {
    const storage = window.sessionStorage
    const keys = storageKeys(storage)
    const resourcePrefix = `${RESOURCE_STORAGE_PREFIX}${teamId}:`
    const resourceKeys = keys.filter((key) => key.startsWith(resourcePrefix))
    const keysToRemove = new Set(resourceKeys)

    resourceKeys.forEach((resourceKey) => {
      const roomId = storage.getItem(resourceKey)
      if (roomId) keysToRemove.add(entryStorageKey(roomId))
    })
    keys.filter((key) => key.startsWith(ENTRY_STORAGE_PREFIX)).forEach((entryKey) => {
      const serialized = storage.getItem(entryKey)
      if (!serialized) return
      try {
        const context = JSON.parse(serialized) as { teamId?: unknown }
        if (context.teamId === teamId) keysToRemove.add(entryKey)
      } catch {
        // 소유 팀을 확인할 수 없는 손상된 다른 entry는 팀 단위 정리에서 건드리지 않는다.
      }
    })
    return removeItems(storage, keysToRemove)
  } catch {
    return false
  }
}

export function clearAllRoundRoomEntryContexts() {
  try {
    const storage = window.sessionStorage
    const keysToRemove = new Set(storageKeys(storage).filter((key) => (
      key.startsWith(ENTRY_STORAGE_PREFIX) || key.startsWith(RESOURCE_STORAGE_PREFIX)
    )))
    return removeItems(storage, keysToRemove)
  } catch {
    return false
  }
}
