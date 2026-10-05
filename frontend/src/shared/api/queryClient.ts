import { matchQuery, MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/ApiError'

export const authSessionQueryKey = ['auth', 'session'] as const

function synchronizeAuthenticationRequired(error: unknown) {
  if (!(error instanceof ApiError)
    || error.status !== 401
    || error.code !== 'AUTHENTICATION_REQUIRED') return

  void queryClient.resetQueries({ queryKey: authSessionQueryKey, exact: true })
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
  },
})


let observedAccount: string | undefined
queryClient.getQueryCache().subscribe(event => {
  if (event.type !== 'updated' || event.action.type !== 'success'
    || !matchQuery({ queryKey: authSessionQueryKey, exact: true }, event.query)) return
  const data = event.query.state.data as { authenticated?: boolean; accountId?: string } | undefined
  if (typeof data?.authenticated !== 'boolean') return
  const account = data.authenticated ? data.accountId : 'anonymous'
  if (!account) return
  const previous = observedAccount
  observedAccount = account
  if (previous !== undefined && previous !== account) {
    void queryClient.cancelQueries({ queryKey: ['teams'] })
    queryClient.removeQueries({ queryKey: ['teams'] })
  }
})
