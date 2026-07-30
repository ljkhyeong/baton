import type { RoleResource } from './types'

const ROUND_ENTRY_CONTEXT_PREFIX = 'baton-round-entry:v1:'
const CANONICAL_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const CANONICAL_ROOM_PATH_PATTERN =
  /^\/room\/([abcdefghjkmnpqrstuvwxyz23456789]{4}(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2})$/

export type RoundEntryContext = Readonly<{
  version: 1
  teamId: string
  seasonId: string
  resourceId: string
  roomId: string
}>

type PrepareRoundEntryContextInput = Readonly<{
  teamId: string
  seasonId: string
  resource: RoleResource
}>

export function roundEntryContextStorageKey(roomId: string) {
  return `${ROUND_ENTRY_CONTEXT_PREFIX}${roomId}`
}

export function prepareRoundEntryContext({
  teamId,
  seasonId,
  resource,
}: PrepareRoundEntryContextInput): boolean {
  const roomId = canonicalRoomId(resource.url)
  if (
    !roomId
    || !CANONICAL_UUID_PATTERN.test(teamId)
    || !CANONICAL_UUID_PATTERN.test(seasonId)
    || !CANONICAL_UUID_PATTERN.test(resource.id)
  ) {
    return false
  }

  const context: RoundEntryContext = {
    version: 1,
    teamId,
    seasonId,
    resourceId: resource.id,
    roomId,
  }
  const serialized = JSON.stringify(context)
  const storageKey = roundEntryContextStorageKey(roomId)

  try {
    window.sessionStorage.setItem(storageKey, serialized)
    return window.sessionStorage.getItem(storageKey) === serialized
  } catch {
    return false
  }
}

function canonicalRoomId(resourceUrl: string): string | null {
  try {
    const parsed = new URL(resourceUrl)
    if (
      parsed.username
      || parsed.password
      || parsed.search
      || parsed.hash
      || (parsed.protocol !== 'https:' && parsed.protocol !== 'http:')
    ) {
      return null
    }
    return CANONICAL_ROOM_PATH_PATTERN.exec(parsed.pathname)?.[1] ?? null
  } catch {
    return null
  }
}
