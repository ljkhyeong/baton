import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'

export const authSessionQueryKey = ['auth', 'session'] as const

function synchronizeAuthenticationRequired(error: unknown) {
  if (!(error instanceof ApiError)
    || error.status !== 401
    || error.code !== 'AUTHENTICATION_REQUIRED') return

  void queryClient.cancelQueries({ queryKey: authSessionQueryKey, exact: true })
    .then(() => queryClient.setQueryData(authSessionQueryKey, { authenticated: false }))
}

export const queryClient = new QueryClient({
  queryCache: new QueryCache({ onError: synchronizeAuthenticationRequired }),
  mutationCache: new MutationCache({ onError: synchronizeAuthenticationRequired }),
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false,
    },
  },
})
