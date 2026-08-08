import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { AuthSession } from '@/features/auth/types'
import { authSessionQueryKey } from '@/features/auth/useAuthSession'
import {
  claimAccountMembership,
  getCurrentAccountMembership,
} from '@/features/membership/api'
import type { ClaimAccountMembershipRequest } from '@/features/membership/types'

export const accountMembershipKeys = {
  all: ['account-membership'] as const,
  current: (accountId: string, teamId: string, accessKey: string) => [
    ...accountMembershipKeys.all,
    'current',
    accountId,
    teamId,
    { accessKey },
  ] as const,
}

type AccountMembershipScope = {
  accountId: string
  teamId: string
  accessKey: string
}

export function useCurrentAccountMembership(scope: AccountMembershipScope) {
  return useQuery({
    queryKey: accountMembershipKeys.current(
      scope.accountId,
      scope.teamId,
      scope.accessKey,
    ),
    queryFn: () => getCurrentAccountMembership(
      scope.accountId,
      scope.teamId,
      scope.accessKey,
    ),
    enabled: Boolean(scope.accountId && scope.teamId && scope.accessKey),
    retry: false,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: 'always',
  })
}

export function useClaimAccountMembership(scope: AccountMembershipScope) {
  const queryClient = useQueryClient()
  const queryKey = accountMembershipKeys.current(
    scope.accountId,
    scope.teamId,
    scope.accessKey,
  )
  return useMutation({
    mutationFn: (request: ClaimAccountMembershipRequest) =>
      claimAccountMembership(request, scope.accountId, scope.accessKey),
    onSuccess: (membership) => {
      const session = queryClient.getQueryData<AuthSession>(authSessionQueryKey)
      if (!session?.authenticated
        || session.accountId.toLowerCase() !== scope.accountId.toLowerCase()) return
      queryClient.setQueryData(queryKey, membership)
    },
  })
}
