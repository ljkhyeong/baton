import type {
  CurrentRoundRoomMapping,
  EndedRoundRoomMapping,
  RoundRoomMapping,
  RoundRoomMappingScope,
} from '@/features/round/types'
import {
  isInstant,
  isJsonObject,
  isNullableInstant,
  isUuid,
} from '@/shared/api/responseValidation'

const ROUND_ROOM_ID_PATTERN =
  /^[abcdefghjkmnpqrstuvwxyz23456789]{4}(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$/
const RESPONSE_FIELDS = [
  'createdAt',
  'endedAt',
  'resourceId',
  'roomId',
  'seasonId',
  'teamId',
] as const
const CURRENT_RESPONSE_FIELDS = ['mapped', ...RESPONSE_FIELDS] as const
const SORTED_CURRENT_RESPONSE_FIELDS = [...CURRENT_RESPONSE_FIELDS].sort()

function sameUuid(left: string, right: string) {
  return left.toLowerCase() === right.toLowerCase()
}

function decodeRoundRoomMapping(value: unknown): RoundRoomMapping {
  if (!isJsonObject(value)) {
    throw new Error('ROUND 방 연결 응답 형식이 올바르지 않습니다.')
  }
  const fields = Object.keys(value).sort()
  if (fields.length !== RESPONSE_FIELDS.length
    || !fields.every((field, index) => field === RESPONSE_FIELDS[index])
    || typeof value.roomId !== 'string'
    || !ROUND_ROOM_ID_PATTERN.test(value.roomId)
    || !isUuid(value.teamId)
    || !isUuid(value.seasonId)
    || !isUuid(value.resourceId)
    || !isInstant(value.createdAt)
    || !isNullableInstant(value.endedAt)) {
    throw new Error('ROUND 방 연결 응답 값이 올바르지 않습니다.')
  }
  return value as RoundRoomMapping
}

function requireScope(
  mapping: RoundRoomMapping,
  scope: RoundRoomMappingScope,
) {
  if (!sameUuid(mapping.teamId, scope.teamId)
    || !sameUuid(mapping.seasonId, scope.seasonId)
    || !sameUuid(mapping.resourceId, scope.resourceId)) {
    throw new Error('ROUND 방 연결 응답 범위가 요청과 일치하지 않습니다.')
  }
  return mapping
}

export function decodeActiveRoundRoomMappingForScope(
  value: unknown,
  scope: RoundRoomMappingScope,
) {
  const mapping = requireScope(decodeRoundRoomMapping(value), scope)
  if (mapping.endedAt !== null) {
    throw new Error('활성 ROUND 방 연결 응답에 종료 시각이 포함되었습니다.')
  }
  return mapping
}

export function decodeCurrentRoundRoomMappingForScope(
  value: unknown,
  scope: RoundRoomMappingScope,
): CurrentRoundRoomMapping {
  if (!isJsonObject(value) || typeof value.mapped !== 'boolean') {
    throw new Error('현재 ROUND 방 연결 응답 형식이 올바르지 않습니다.')
  }
  if (!value.mapped) {
    const fields = Object.keys(value)
    if (fields.length !== 1 || fields[0] !== 'mapped') {
      throw new Error('연결되지 않은 ROUND 방 응답 형식이 올바르지 않습니다.')
    }
    return { mapped: false }
  }
  const fields = Object.keys(value).sort()
  if (fields.length !== CURRENT_RESPONSE_FIELDS.length
    || !fields.every((field, index) => field === SORTED_CURRENT_RESPONSE_FIELDS[index])) {
    throw new Error('현재 ROUND 방 연결 응답 형식이 올바르지 않습니다.')
  }
  const mappingValue = Object.fromEntries(
    RESPONSE_FIELDS.map((field) => [field, value[field]]),
  )
  const mapping = decodeActiveRoundRoomMappingForScope(mappingValue, scope)
  return { mapped: true, ...mapping }
}

export function decodeEndedRoundRoomMappingForScope(
  value: unknown,
  scope: RoundRoomMappingScope,
): EndedRoundRoomMapping {
  const mapping = requireScope(decodeRoundRoomMapping(value), scope)
  if (mapping.endedAt === null) {
    throw new Error('종료된 ROUND 방 연결 응답에 종료 시각이 없습니다.')
  }
  return mapping as EndedRoundRoomMapping
}
