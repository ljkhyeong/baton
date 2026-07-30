import type { paths } from '@/generated/api'

type ApiPath = keyof paths
type HttpMethod = 'delete' | 'get' | 'head' | 'options' | 'patch' | 'post' | 'put' | 'trace'

type SupportedMethod<Path extends ApiPath> = {
  [Method in HttpMethod]: Method extends keyof paths[Path]
    ? [NonNullable<paths[Path][Method]>] extends [never]
      ? never
      : Uppercase<Method>
    : never
}[HttpMethod]

type PathParameterName<Path extends string> =
  Path extends `${string}{${infer Parameter}}${infer Rest}`
    ? Parameter | PathParameterName<Rest>
    : never

type PathParameters<Path extends string> = Record<PathParameterName<Path>, string>

function defineEndpoint<Path extends ApiPath, Method extends SupportedMethod<Path>>(
  path: Path,
  method: Method,
) {
  return { method, path } as const
}

function resolveEndpointPath<Path extends ApiPath>(
  endpoint: { path: Path },
  parameters: PathParameters<Path>,
) {
  const values = parameters as Record<string, string>
  return endpoint.path.replace(/\{([^}]+)}/g, (placeholder, parameter: string) => {
    const value = values[parameter]
    if (value === undefined) throw new Error(`API 경로 변수가 없습니다: ${placeholder}`)
    return encodeURIComponent(value)
  })
}

const identityContract = {
  googleAuthorization: defineEndpoint('/api/v1/auth/oidc/authorization/google', 'GET'),
  session: defineEndpoint('/api/v1/auth/session', 'GET'),
  invitationPreview: defineEndpoint('/api/v1/identity/invitations/preview', 'POST'),
  invitationAcceptance: defineEndpoint('/api/v1/identity/invitations/accept', 'POST'),
  logout: defineEndpoint('/api/v1/session/logout', 'POST'),
  teamMembership: defineEndpoint('/api/v1/teams/{teamId}/membership', 'GET'),
  memberInvitations: defineEndpoint(
    '/api/v1/teams/{teamId}/member-invitations',
    'GET',
  ),
  issueMemberInvitation: defineEndpoint(
    '/api/v1/teams/{teamId}/member-invitations',
    'POST',
  ),
  memberInvitationRevocation: defineEndpoint(
    '/api/v1/teams/{teamId}/member-invitations/{invitationId}/revocation',
    'POST',
  ),
} as const

export const identityEndpoints = {
  googleAuthorization: identityContract.googleAuthorization.path,
  session: identityContract.session.path,
  invitationPreview: identityContract.invitationPreview.path,
  invitationAcceptance: identityContract.invitationAcceptance.path,
  logout: identityContract.logout.path,
  teamMembership: (teamId: string) =>
    resolveEndpointPath(identityContract.teamMembership, { teamId }),
  memberInvitations: (teamId: string) =>
    resolveEndpointPath(identityContract.memberInvitations, { teamId }),
  memberInvitationRevocation: (teamId: string, invitationId: string) =>
    resolveEndpointPath(identityContract.memberInvitationRevocation, {
      teamId,
      invitationId,
    }),
} as const
