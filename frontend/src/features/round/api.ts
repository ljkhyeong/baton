import { getCsrfToken } from '@/features/auth/api'
import {
  decodeActiveRoundRoomMappingForScope,
  decodeCurrentRoundRoomMappingsForScope,
  decodeEndedRoundRoomMappingForScope,
} from '@/features/round/responseDecoder'
import type {
  CreateRoundRoomMappingRequest,
  CurrentRoundRoomMappings,
  EndedRoundRoomMapping,
  RoundRoomMapping,
  RoundRoomMappingScope,
  RoundRoomMappingsScope,
} from '@/features/round/types'
import { apiRequest } from '@/shared/api/client'

const ROUND_ROOM_MAPPINGS_PATH = '/api/v1/round-room-mappings'
const ACCESS_KEY_HEADER = 'X-Baton-Access-Key'

async function roundMutationHeaders(accessKey: string) {
  const csrf = await getCsrfToken()
  return {
    [ACCESS_KEY_HEADER]: accessKey,
    [csrf.csrfHeaderName]: csrf.csrfToken,
  }
}

export function getCurrentRoundRoomMappings(
  scope: RoundRoomMappingsScope,
  signal?: AbortSignal,
): Promise<CurrentRoundRoomMappings> {
  return apiRequest(ROUND_ROOM_MAPPINGS_PATH, {
    method: 'GET',
    headers: { [ACCESS_KEY_HEADER]: scope.accessKey },
    query: {
      seasonId: scope.seasonId,
      teamId: scope.teamId,
    },
    signal,
    decode: (value) => decodeCurrentRoundRoomMappingsForScope(value, scope),
  })
}

export async function createOrReuseRoundRoomMapping(
  scope: RoundRoomMappingScope,
): Promise<RoundRoomMapping> {
  const request = {
    resourceId: scope.resourceId,
    seasonId: scope.seasonId,
    teamId: scope.teamId,
  } satisfies CreateRoundRoomMappingRequest
  return apiRequest(ROUND_ROOM_MAPPINGS_PATH, {
    method: 'POST',
    body: request,
    headers: await roundMutationHeaders(scope.accessKey),
    decode: (value) => decodeActiveRoundRoomMappingForScope(value, scope),
  })
}

export async function endRoundRoomMapping(
  scope: RoundRoomMappingScope,
  roomId: string,
): Promise<EndedRoundRoomMapping> {
  return apiRequest(`${ROUND_ROOM_MAPPINGS_PATH}/${encodeURIComponent(roomId)}`, {
    method: 'DELETE',
    headers: await roundMutationHeaders(scope.accessKey),
    decode: (value) => decodeEndedRoundRoomMappingForScope(value, scope),
  })
}
