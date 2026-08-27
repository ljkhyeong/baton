import { queryOptions, useQuery } from '@tanstack/react-query'
import { getAuthSession } from '@/features/auth/api'

export const authSessionQueryKey = ['auth', 'session'] as const

export const authSessionQueryOptions = queryOptions({
  queryKey: authSessionQueryKey,
  queryFn: getAuthSession,
  retry: false,
  staleTime: 0,
  refetchOnWindowFocus: 'always',
})

export function useAuthSession() {
  return useQuery(authSessionQueryOptions)
}
