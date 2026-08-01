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

export const workspaceEndpoints = {
  createWorkspace: defineEndpoint('/api/v1/workspaces', 'POST'),
  getWorkspace: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/workspace',
    'GET',
  ),
  rotateAccessKey: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/access-key/rotate',
    'POST',
  ),
  createMember: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/members',
    'POST',
  ),
  updateMember: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}',
    'PUT',
  ),
  updateMemberDeactivation: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/members/{memberId}/deactivation',
    'PATCH',
  ),
  createRole: defineEndpoint('/api/v1/teams/{teamId}/seasons/{seasonId}/roles', 'POST'),
  updateRole: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}',
    'PUT',
  ),
  prepareRoleHandoff: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs',
    'POST',
  ),
  transferRoleHandoff: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/transfer',
    'PATCH',
  ),
  acceptRoleHandoff: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/acceptance',
    'PATCH',
  ),
  cancelRoleHandoff: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}/handoffs/{handoffId}/cancellation',
    'PATCH',
  ),
  createRoutine: defineEndpoint('/api/v1/teams/{teamId}/seasons/{seasonId}/routines', 'POST'),
  updateRoutine: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}',
    'PUT',
  ),
  updateRoutineArchive: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/archive',
    'PATCH',
  ),
  createSeasonRound: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds',
    'POST',
  ),
  updateSeasonRound: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}',
    'PUT',
  ),
  updateSeasonRoundArchive: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/archive',
    'PATCH',
  ),
  updateRoutineExecutionCompletion: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/rounds/{roundId}/routine-executions/{executionId}/completion',
    'PATCH',
  ),
  createDecision: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions',
    'POST',
  ),
  updateDecision: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}',
    'PUT',
  ),
  updateDecisionArchive: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions/{decisionId}/archive',
    'PATCH',
  ),
  createHandoffItem: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items',
    'POST',
  ),
  updateHandoffItem: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}',
    'PUT',
  ),
  updateHandoffItemCompletion: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion',
    'PATCH',
  ),
  updateHandoffItemArchive: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/archive',
    'PATCH',
  ),
  createRoleResource: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources',
    'POST',
  ),
  updateRoleResource: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/role-resources/{resourceId}',
    'PUT',
  ),
} as const

export const seasonLifecycleEndpoints = {
  updateSeason: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}',
    'PUT',
  ),
  updateRoundSchedule: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/round-schedule',
    'PUT',
  ),
  updateSeasonEnding: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/ending',
    'PATCH',
  ),
  createNextSeason: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/successor',
    'POST',
  ),
} as const

export function resolveEndpointPath<Path extends ApiPath>(
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
