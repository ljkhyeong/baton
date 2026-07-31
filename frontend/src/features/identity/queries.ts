import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { clearAccountScopedClientState } from '@/shared/auth/accountScopedState'
import {
  acceptInvitation,
  getIdentitySession,
  getTeamMembership,
  issueMemberInvitation,
  listMemberInvitations,
  logoutSession,
  previewInvitation,
  revokeMemberInvitation,
} from './api'
import type { CsrfCredential, IdentitySession } from './types'

export const identityKeys = {
  all: ['identity'] as const,
  session: () => [...identityKeys.all, 'session'] as const,
  teams: () => [...identityKeys.all, 'teams'] as const,
  team: (teamId: string) => [...identityKeys.teams(), teamId] as const,
  membership: (teamId: string, accountId: string) =>
    [...identityKeys.team(teamId), 'accounts', accountId, 'membership'] as const,
  invitations: (teamId: string, accountId: string) =>
    [...identityKeys.team(teamId), 'accounts', accountId, 'member-invitations'] as const,
}

export class AccountSessionContextError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AccountSessionContextError'
  }
}

export function useIdentitySessionQuery() {
  return useQuery({
    queryKey: identityKeys.session(),
    queryFn: getIdentitySession,
    retry: false,
    staleTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: true,
  })
}

export function useTeamMembershipQuery(teamId: string, accountId: string) {
  return useQuery({
    queryKey: identityKeys.membership(teamId, accountId),
    queryFn: () => getTeamMembership(teamId),
    enabled: Boolean(teamId && accountId),
    retry: false,
  })
}

export function useMemberInvitationsQuery(
  teamId: string,
  accountId: string,
  enabled: boolean,
) {
  return useQuery({
    queryKey: identityKeys.invitations(teamId, accountId),
    queryFn: () => listMemberInvitations(teamId),
    enabled: Boolean(teamId && enabled),
    retry: false,
  })
}

export async function currentCsrfCredential(
  queryClient: QueryClient,
  required = true,
): Promise<CsrfCredential | null> {
  const session = await currentIdentitySession(queryClient)
  if (!session.authenticated || !session.csrfHeaderName || !session.csrfToken) {
    if (!required) return null
    throw new Error('로그인 세션을 다시 확인해 주세요.')
  }
  return {
    headerName: session.csrfHeaderName,
    token: session.csrfToken,
  }
}

export function currentIdentitySession(queryClient: QueryClient) {
  return queryClient.fetchQuery({
    queryKey: identityKeys.session(),
    queryFn: getIdentitySession,
    staleTime: 0,
  })
}

export async function currentAccountCsrfCredential(
  queryClient: QueryClient,
  expectedAccountId: string,
) {
  const session = await currentIdentitySession(queryClient)
  if (!session.authenticated || !session.csrfHeaderName || !session.csrfToken) {
    throw new AccountSessionContextError(
      '같은 계정으로 다시 로그인한 뒤 생성 결과를 확인해 주세요.',
    )
  }
  if (session.accountId !== expectedAccountId) {
    throw new AccountSessionContextError(
      '작업 공간 생성을 시작한 계정으로 다시 전환해 주세요.',
    )
  }
  return {
    accountId: expectedAccountId,
    credential: {
      headerName: session.csrfHeaderName,
      token: session.csrfToken,
    },
  }
}

export function useInvitationPreviewMutation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: [...identityKeys.all, 'invitation-preview'],
    gcTime: 0,
    mutationFn: async (token: string) => {
      const credential = await currentCsrfCredential(queryClient)
      if (!credential) throw new Error('로그인 세션을 다시 확인해 주세요.')
      return previewInvitation(token, credential)
    },
  })
}

export function useAcceptInvitationMutation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: [...identityKeys.all, 'invitation-acceptance'],
    gcTime: 0,
    mutationFn: async (token: string) => {
      const credential = await currentCsrfCredential(queryClient)
      if (!credential) throw new Error('로그인 세션을 다시 확인해 주세요.')
      return acceptInvitation(token, credential)
    },
    onSuccess: async (accepted) => {
      await queryClient.invalidateQueries({ queryKey: identityKeys.all })
      await queryClient.invalidateQueries({
        queryKey: identityKeys.team(accepted.teamId),
      })
    },
  })
}

export function useLogoutSessionMutation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: [...identityKeys.all, 'logout'],
    mutationFn: async () => {
      const credential = await currentCsrfCredential(queryClient)
      if (!credential) throw new Error('로그인 세션을 다시 확인해 주세요.')
      return logoutSession(credential)
    },
    onSuccess: async () => {
      const previous = queryClient.getQueryData<IdentitySession>(
        identityKeys.session(),
      )
      await clearAccountScopedClientState(
        queryClient,
        previous?.authenticated ? previous.accountId ?? undefined : undefined,
      )
      queryClient.removeQueries({ queryKey: identityKeys.teams() })
      queryClient.setQueryData<IdentitySession>(identityKeys.session(), {
        authenticated: false,
        accountId: null,
        csrfHeaderName: null,
        csrfToken: null,
        oidcEnabled: previous?.oidcEnabled ?? false,
      })
    },
  })
}

export function useIssueMemberInvitationMutation(
  teamId: string,
  accountId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: [
      ...identityKeys.all,
      'member-invitation-issue',
      teamId,
      accountId,
    ],
    gcTime: 0,
    mutationFn: async ({
      memberId,
      idempotencyKey,
    }: {
      memberId: string
      idempotencyKey: string
    }) => {
      const credential = await currentCsrfCredential(queryClient)
      if (!credential) throw new Error('로그인 세션을 다시 확인해 주세요.')
      return issueMemberInvitation(teamId, memberId, idempotencyKey, credential)
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: identityKeys.invitations(teamId, accountId),
      })
    },
  })
}

export function useRevokeMemberInvitationMutation(
  teamId: string,
  accountId: string,
) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationKey: [
      ...identityKeys.all,
      'member-invitation-revocation',
      teamId,
      accountId,
    ],
    mutationFn: async (invitationId: string) => {
      const credential = await currentCsrfCredential(queryClient)
      if (!credential) throw new Error('로그인 세션을 다시 확인해 주세요.')
      return revokeMemberInvitation(teamId, invitationId, credential)
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: identityKeys.invitations(teamId, accountId),
      })
    },
  })
}
