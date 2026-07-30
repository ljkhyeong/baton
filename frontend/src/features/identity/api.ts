import { apiRequest } from '@/shared/api/client'
import { identityEndpoints } from './contract'
import type {
  AcceptInvitationResponse,
  CsrfCredential,
  IdentitySession,
  InvitationPreview,
  IssueMemberInvitationResponse,
  MemberInvitation,
  RevokeMemberInvitationResponse,
  TeamMembership,
} from './types'

function csrfHeaders(credential: CsrfCredential): HeadersInit {
  return { [credential.headerName]: credential.token }
}

export function getIdentitySession() {
  return apiRequest<IdentitySession>(identityEndpoints.session)
}

export function getTeamMembership(teamId: string) {
  return apiRequest<TeamMembership>(identityEndpoints.teamMembership(teamId))
}

export function previewInvitation(token: string, credential: CsrfCredential) {
  return apiRequest<InvitationPreview>(identityEndpoints.invitationPreview, {
    method: 'POST',
    headers: csrfHeaders(credential),
    body: { token },
  })
}

export function acceptInvitation(token: string, credential: CsrfCredential) {
  return apiRequest<AcceptInvitationResponse>(identityEndpoints.invitationAcceptance, {
    method: 'POST',
    headers: csrfHeaders(credential),
    body: { token },
  })
}

export function logoutSession(credential: CsrfCredential) {
  return apiRequest<void>(identityEndpoints.logout, {
    method: 'POST',
    headers: csrfHeaders(credential),
  })
}

export function issueMemberInvitation(
  teamId: string,
  memberId: string,
  idempotencyKey: string,
  credential: CsrfCredential,
) {
  return apiRequest<IssueMemberInvitationResponse>(
    identityEndpoints.memberInvitations(teamId),
    {
      method: 'POST',
      headers: {
        ...csrfHeaders(credential),
        'Idempotency-Key': idempotencyKey,
      },
      body: { memberId },
    },
  )
}
export function listMemberInvitations(teamId: string) {
  return apiRequest<MemberInvitation[]>(identityEndpoints.memberInvitations(teamId))
}

export function revokeMemberInvitation(
  teamId: string,
  invitationId: string,
  credential: CsrfCredential,
) {
  return apiRequest<RevokeMemberInvitationResponse>(
    identityEndpoints.memberInvitationRevocation(teamId, invitationId),
    {
      method: 'POST',
      headers: csrfHeaders(credential),
    },
  )
}
