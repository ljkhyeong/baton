import { queryOptions, useQuery } from '@tanstack/react-query'
import { getAccountSecurity } from '@/features/auth/api'

export const accountSecurityKeys = {
  all: ['auth', 'account'] as const,
  current: (accountId: string) => [...accountSecurityKeys.all, accountId] as const,
}

export function useAccountSecurity(accountId: string) {
  return useQuery(queryOptions({
    queryKey: accountSecurityKeys.current(accountId),
    queryFn: getAccountSecurity,
    enabled: Boolean(accountId),
    retry: false,
    staleTime: 0,
  }))
}
