import type {
  CurrentRoundRoomMappings,
  EndedRoundRoomMapping,
  RoundRoomMapping,
  RoundRoomMappingScope,
  RoundRoomMappingsScope,
} from '@/features/round/types'
import {
  isInstant,
  isJsonObject,
  isNullableInstant,
  isRoundRoomId,
  isSameUuid,
  isUuid,
} from '@/shared/api/responseValidation'

function decodeRoundRoomMapping(value: unknown): RoundRoomMapping {
  if (!isJsonObject(value)) {
    throw new Error('ROUND 방 연결 응답 형식이 올바르지 않습니다.')
  }
  if (!isRoundRoomId(value.roomId)
    || !isUuid(value.teamId)
    || !isUuid(value.seasonId)
    || !isUuid(value.resourceId)
    || !isInstant(value.createdAt)
    || !isNullableInstant(value.endedAt)) {
    throw new Error('ROUND 방 연결 응답 값이 올바르지 않습니다.')
  }
  return {
    roomId: value.roomId,
    teamId: value.teamId,
    seasonId: value.seasonId,
    resourceId: value.resourceId,
    createdAt: value.createdAt,
    endedAt: value.endedAt,
  }
}

function requireScope(
  mapping: RoundRoomMapping,
  scope: RoundRoomMappingScope,
) {
  if (!isSameUuid(mapping.teamId, scope.teamId)
    || !isSameUuid(mapping.seasonId, scope.seasonId)
    || !isSameUuid(mapping.resourceId, scope.resourceId)) {
    throw new Error('이 팀의 방 정보를 확인하지 못했습니다. 다시 확인해 주세요.')
  }
  return mapping
}

function requireListScope(
  mapping: RoundRoomMapping,
  scope: RoundRoomMappingsScope,
) {
  if (!isSameUuid(mapping.teamId, scope.teamId)
    || !isSameUuid(mapping.seasonId, scope.seasonId)) {
    throw new Error('이 팀의 방 정보를 확인하지 못했습니다. 다시 확인해 주세요.')
  }
  return mapping
}

export function decodeActiveRoundRoomMappingForScope(
  value: unknown,
  scope: RoundRoomMappingScope,
) {
  return requireScope(decodeRoundRoomMapping(value), scope)
}

export function decodeCurrentRoundRoomMappingsForScope(
  value: unknown,
  scope: RoundRoomMappingsScope,
): CurrentRoundRoomMappings {
  if (!isJsonObject(value)
    || !Array.isArray(value.mappings)) {
    throw new Error('현재 ROUND 방 연결 목록 응답 형식이 올바르지 않습니다.')
  }
  const mappings = value.mappings.map((candidate) => (
    requireListScope(decodeRoundRoomMapping(candidate), scope)
  ))
  return { mappings }
}

export function decodeEndedRoundRoomMappingForScope(
  value: unknown,
  scope: RoundRoomMappingScope,
): EndedRoundRoomMapping {
  const mapping = requireScope(decodeRoundRoomMapping(value), scope)
  const endedAt = mapping.endedAt
  if (!isInstant(endedAt)) {
    throw new Error('종료된 ROUND 방 연결 응답 형식이 올바르지 않습니다.')
  }
  return {
    roomId: mapping.roomId,
    teamId: mapping.teamId,
    seasonId: mapping.seasonId,
    resourceId: mapping.resourceId,
    createdAt: mapping.createdAt,
    endedAt,
  }
}
