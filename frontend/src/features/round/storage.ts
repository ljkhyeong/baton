import type {
  RoundRoomEntryContext,
  RoundRoomMapping,
  RoundRoomMappingScope,
} from '@/features/round/types'

const ENTRY_STORAGE_PREFIX = 'baton-round-entry:v1:'
const RESOURCE_STORAGE_PREFIX = 'baton-round-resource:v1:'
const ROUND_ROOM_ID_PATTERN =
  /^[abcdefghjkmnpqrstuvwxyz23456789]{4}(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$/
const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const ENTRY_FIELDS = ['resourceId', 'roomId', 'seasonId', 'teamId', 'version'] as const

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
  const fields = Object.keys(candidate).sort()
  return fields.length === ENTRY_FIELDS.length
    && fields.every((field, index) => field === ENTRY_FIELDS[index])
    && candidate.version === 1
    && typeof candidate.teamId === 'string'
    && CANONICAL_UUID_PATTERN.test(candidate.teamId)
    && candidate.teamId === scope.teamId
    && typeof candidate.seasonId === 'string'
    && CANONICAL_UUID_PATTERN.test(candidate.seasonId)
    && candidate.seasonId === scope.seasonId
    && typeof candidate.resourceId === 'string'
    && CANONICAL_UUID_PATTERN.test(candidate.resourceId)
    && candidate.resourceId === scope.resourceId
    && typeof candidate.roomId === 'string'
    && ROUND_ROOM_ID_PATTERN.test(candidate.roomId)
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
    const stored = storage.getItem(entryStorageKey(mapping.roomId)) === serialized
      && storage.getItem(resourceStorageKey(scope)) === mapping.roomId
    if (!stored) removeStoredContext(storage, scope, mapping.roomId)
    return stored
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
    if (!roomId || !ROUND_ROOM_ID_PATTERN.test(roomId)) {
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
    return parsed
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
    return storage.getItem(resourceStorageKey(scope)) === null
      && storage.getItem(entryStorageKey(roomId)) === null
  } catch {
    return false
  }
}
