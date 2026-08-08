import { useQuery } from '@tanstack/react-query'
import { getAuthSession } from '@/features/auth/api'

export const authSessionQueryKey = ['auth', 'session'] as const

export function useAuthSession() {
  return useQuery({
    queryKey: authSessionQueryKey,
    queryFn: getAuthSession,
    retry: false,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: 'always',
  })
}
