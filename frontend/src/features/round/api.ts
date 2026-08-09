import { getCsrfToken } from '@/features/auth/api'
import {
  decodeActiveRoundRoomMappingForScope,
  decodeEndedRoundRoomMappingForScope,
} from '@/features/round/responseDecoder'
import type {
  CreateRoundRoomMappingRequest,
  EndedRoundRoomMapping,
  RoundRoomMapping,
  RoundRoomMappingScope,
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
