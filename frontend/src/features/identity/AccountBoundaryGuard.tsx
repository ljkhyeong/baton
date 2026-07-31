import { useEffect, useRef } from 'react'
import type { ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { clearAccountScopedClientState } from '@/shared/auth/accountScopedState'
import { useIdentitySessionQuery } from './queries'

export default function AccountBoundaryGuard({
  children,
}: {
  children: ReactNode
}) {
  const queryClient = useQueryClient()
  const sessionQuery = useIdentitySessionQuery()
  const loadedAccountIdentity = sessionQuery.isSuccess
    ? sessionQuery.data.authenticated
      ? sessionQuery.data.accountId ?? 'anonymous'
      : 'anonymous'
    : ''
  const previousAccountIdentityRef = useRef<string | null>(null)

  useEffect(() => {
    if (!loadedAccountIdentity) return
    const previousAccountIdentity = previousAccountIdentityRef.current
    previousAccountIdentityRef.current = loadedAccountIdentity
    if (previousAccountIdentity === null
      || previousAccountIdentity === loadedAccountIdentity) return
    void clearAccountScopedClientState(
      queryClient,
      previousAccountIdentity === 'anonymous' ? undefined : previousAccountIdentity,
    )
  }, [loadedAccountIdentity, queryClient])

  return children
}
