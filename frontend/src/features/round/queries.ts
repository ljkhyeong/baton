import { useQuery } from '@tanstack/react-query'
import { getCurrentRoundRoomMappings } from '@/features/round/api'
import type { RoundRoomMappingsScope } from '@/features/round/types'

export const roundRoomMappingKeys = {
  all: ['round-room-mapping'] as const,
  current: (accountId: string, scope: RoundRoomMappingsScope) => [
    ...roundRoomMappingKeys.all,
    'current',
    accountId,
    scope.teamId,
    scope.seasonId,
    { accessKey: scope.accessKey },
  ] as const,
}

export function useCurrentRoundRoomMappings(
  accountId: string,
  scope: RoundRoomMappingsScope,
  enabled: boolean,
) {
  return useQuery({
    queryKey: roundRoomMappingKeys.current(accountId, scope),
    queryFn: ({ signal }) => getCurrentRoundRoomMappings(scope, signal),
    enabled: enabled && Boolean(
      accountId
      && scope.accessKey
      && scope.seasonId
      && scope.teamId,
    ),
    retry: false,
    staleTime: 0,
    refetchOnWindowFocus: 'always',
  })
}
