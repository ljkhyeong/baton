import { useQuery } from '@tanstack/react-query'
import { getAccountSecurity } from '@/features/auth/api'

const accountSecurityKeys = {
  all: ['auth', 'account'] as const,
  current: (accountId: string) => [...accountSecurityKeys.all, accountId] as const,
}

export function useAccountSecurity(accountId: string) {
  return useQuery({
    queryKey: accountSecurityKeys.current(accountId),
    queryFn: getAccountSecurity,
    retry: false,
    staleTime: 0,
  })
}
