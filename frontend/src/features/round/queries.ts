import { useQuery } from '@tanstack/react-query'
import { getCurrentRoundRoomMapping } from '@/features/round/api'
import type { RoundRoomMappingScope } from '@/features/round/types'

export const roundRoomMappingKeys = {
  all: ['round-room-mapping'] as const,
  current: (accountId: string, scope: RoundRoomMappingScope) => [
    ...roundRoomMappingKeys.all,
    'current',
    accountId,
    scope.teamId,
    scope.seasonId,
    scope.resourceId,
    { accessKey: scope.accessKey },
  ] as const,
}

export function useCurrentRoundRoomMapping(
  accountId: string,
  scope: RoundRoomMappingScope,
  enabled: boolean,
) {
  return useQuery({
    queryKey: roundRoomMappingKeys.current(accountId, scope),
    queryFn: () => getCurrentRoundRoomMapping(scope),
    enabled: enabled && Boolean(
      accountId
      && scope.accessKey
      && scope.resourceId
      && scope.seasonId
      && scope.teamId,
    ),
    retry: false,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: 'always',
  })
}
