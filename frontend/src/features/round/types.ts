import type { operations } from '@/generated/api'

type CreateRoundRoomMappingOperation = operations['createRoundRoomMapping']
type EndRoundRoomMappingOperation = operations['endRoundRoomMapping']
type GetCurrentRoundRoomMappingOperation = operations['getCurrentRoundRoomMapping']

export type CreateRoundRoomMappingRequest =
  CreateRoundRoomMappingOperation['requestBody']['content']['application/json']

export type RoundRoomMapping =
  CreateRoundRoomMappingOperation['responses'][200]['content']['application/json']

export type EndedRoundRoomMapping =
  EndRoundRoomMappingOperation['responses'][200]['content']['application/json']

export type CurrentRoundRoomMapping =
  GetCurrentRoundRoomMappingOperation['responses'][200]['content']['application/json']

export type RoundRoomMappingScope = {
  accessKey: string
  resourceId: string
  seasonId: string
  teamId: string
}

export type RoundRoomEntryContext = Pick<
  RoundRoomMapping,
  'resourceId' | 'roomId' | 'seasonId' | 'teamId'
> & {
  version: 1
}
