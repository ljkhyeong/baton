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
  createRole: defineEndpoint('/api/v1/teams/{teamId}/seasons/{seasonId}/roles', 'POST'),
  updateRole: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/roles/{roleId}',
    'PUT',
  ),
  createRoutine: defineEndpoint('/api/v1/teams/{teamId}/seasons/{seasonId}/routines', 'POST'),
  updateRoutine: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}',
    'PUT',
  ),
  updateRoutineCompletion: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/routines/{routineId}/completion',
    'PATCH',
  ),
  createDecision: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/decisions',
    'POST',
  ),
  createHandoffItem: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items',
    'POST',
  ),
  updateHandoffItemCompletion: defineEndpoint(
    '/api/v1/teams/{teamId}/seasons/{seasonId}/handoff-items/{itemId}/completion',
    'PATCH',
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
