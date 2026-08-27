import { useQuery } from '@tanstack/react-query'
import { getAuthCapabilities } from '@/features/auth/api'

const authCapabilitiesQueryKey = ['auth', 'providers'] as const

export function useAuthCapabilities() {
  return useQuery({
    queryKey: authCapabilitiesQueryKey,
    queryFn: getAuthCapabilities,
    retry: false,
  })
}
