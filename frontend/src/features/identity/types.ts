import type { operations } from '@/generated/api'

type OperationId = keyof operations

type JsonResponse<
  Id extends OperationId,
  Status extends keyof operations[Id]['responses'],
> = operations[Id]['responses'][Status] extends {
  content: { 'application/json': infer Body }
}
  ? Body
  : never

export type IdentitySession = JsonResponse<'getIdentitySession', 200>
export type TeamMembership = JsonResponse<'getTeamMembership', 200>
export type IdentityRole = TeamMembership['role']
export type InvitationPreview = JsonResponse<'previewInvitation', 200>
export type AcceptInvitationResponse = JsonResponse<'acceptInvitation', 200>
export type IssueMemberInvitationResponse = JsonResponse<'issueMemberInvitation', 201>
export type MemberInvitation = JsonResponse<'listMemberInvitations', 200>[number]
export type RevokeMemberInvitationResponse = JsonResponse<'revokeMemberInvitation', 200>

export type CsrfCredential = {
  headerName: string
  token: string
}
