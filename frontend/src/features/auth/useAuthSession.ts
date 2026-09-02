import { queryOptions, useQuery } from '@tanstack/react-query'
import { getAuthSession } from '@/features/auth/api'
import { authSessionQueryKey } from '@/shared/api/queryClient'

export { authSessionQueryKey }

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
