import {
  createPublicKey,
  randomUUID,
  type JsonWebKey as NodeJsonWebKey,
  verify as verifySignature,
} from 'node:crypto'

import { expect, test, type APIRequestContext } from '@playwright/test'

import { requireLocalhostHttpOrigin } from '../support/loopback-url'

const backendBaseURL = requireLocalhostHttpOrigin(
  process.env.BATON_FULLSTACK_BACKEND_BASE_URL,
  'BATON_FULLSTACK_BACKEND_BASE_URL',
)
const bootstrapKey = process.env.BATON_FULLSTACK_IDENTITY_BOOTSTRAP_KEY
const creationKey = process.env.BATON_FULLSTACK_CREATION_KEY
const roomId = 'abcd-efgh-jkmn'

if (!bootstrapKey) {
  throw new Error('BATON_FULLSTACK_IDENTITY_BOOTSTRAP_KEY가 필요합니다.')
}
if (!creationKey) {
  throw new Error('BATON_FULLSTACK_CREATION_KEY가 필요합니다.')
}

test.use({ trace: 'off', video: 'off' })

interface CreatedWorkspace {
  teamId: string
  seasonId: string
  accessKey: string
}

interface WorkspaceView {
  members: Array<{ id: string; name: string }>
}

interface BootstrapInvitation {
  token: string
}

interface IdentitySession {
  authenticated: boolean
  accountId: string | null
  csrfHeaderName: string | null
  csrfToken: string | null
  oidcEnabled: boolean
}

interface Role {
  id: string
}

interface RoleResource {
  id: string
}

interface RefreshResult {
  status: number
  cacheControl: string | null
  body: Record<string, unknown>
}

interface RoundPublicJwk extends NodeJsonWebKey {
  alg: string
  kid: string
  use: string
}

interface RoundJwkSet {
  keys: RoundPublicJwk[]
}

interface RoundGrantHeader {
  alg: string
  kid: string
  typ: string
}

interface RoundGrantClaims {
  aud: string
  exp: number
  iat: number
  iss: string
  jti: string
  role: string
  room_id: string
  study_id: string
  sub: string
}

function backend(path: string): string {
  return new URL(path, backendBaseURL).toString()
}

function decodeJwtJson<T>(segment: string): T {
  try {
    return JSON.parse(Buffer.from(segment, 'base64url').toString('utf8')) as T
  } catch {
    throw new Error('ROUND 참여권 JWT JSON을 해석하지 못했습니다.')
  }
}

function verifyRoundGrant(
  token: string,
  jwkSet: RoundJwkSet,
): { header: RoundGrantHeader; claims: RoundGrantClaims; signatureValid: boolean } {
  const segments = token.split('.')
  if (segments.length !== 3 || segments.some((segment) => !segment)) {
    throw new Error('ROUND 참여권 JWT compact 형식이 올바르지 않습니다.')
  }
  const [encodedHeader, encodedClaims, encodedSignature] = segments as [
    string,
    string,
    string,
  ]
  const header = decodeJwtJson<RoundGrantHeader>(encodedHeader)
  const claims = decodeJwtJson<RoundGrantClaims>(encodedClaims)
  const publicJwk = jwkSet.keys.find((key) => key.kid === header.kid)
  if (!publicJwk) {
    throw new Error('ROUND 참여권 kid에 대응하는 공개 JWK가 없습니다.')
  }
  const signatureValid = verifySignature(
    'RSA-SHA256',
    Buffer.from(`${encodedHeader}.${encodedClaims}`),
    createPublicKey({ key: publicJwk, format: 'jwk' }),
    Buffer.from(encodedSignature, 'base64url'),
  )
  return { header, claims, signatureValid }
}

async function requireJson<T>(
  request: APIRequestContext,
  method: 'get' | 'post',
  path: string,
  options?: Parameters<APIRequestContext['post']>[1],
): Promise<T> {
  const response = await request[method](backend(path), options).catch(() => {
    throw new Error(`${method.toUpperCase()} ${path} 요청에 실패했습니다.`)
  })
  expect(response.ok(), `${method.toUpperCase()} ${path} 응답`).toBe(true)
  return response.json() as Promise<T>
}

test('실제 OIDC 세션과 구성원 권한으로 ROUND 참여권을 갱신한다', async ({
  context,
  page,
  request,
}) => {
  const workspace = await requireJson<CreatedWorkspace>(
    request,
    'post',
    '/api/v1/workspaces',
    {
      headers: {
        'Idempotency-Key': randomUUID(),
        'X-Baton-Creation-Key': creationKey,
      },
      data: {
        teamName: 'OIDC ROUND 풀스택 검증팀',
        seasonName: '2026 파일럿',
        startDate: '2026-07-01',
        endDate: '2026-12-31',
        memberNames: ['OIDC 소유자'],
      },
    },
  )
  const workspaceView = await requireJson<WorkspaceView>(
    request,
    'get',
    `/api/v1/teams/${workspace.teamId}/seasons/${workspace.seasonId}/workspace`,
    { headers: { 'X-Baton-Access-Key': workspace.accessKey } },
  )
  expect(workspaceView.members).toHaveLength(1)
  const ownerMemberId = workspaceView.members[0]?.id
  expect(ownerMemberId).toBeTruthy()

  const invitation = await requireJson<BootstrapInvitation>(
    request,
    'post',
    '/api/v1/identity/bootstrap-invitations',
    {
      headers: {
        'Idempotency-Key': randomUUID(),
        'X-Baton-Identity-Bootstrap-Key': bootstrapKey,
      },
      data: {
        teamId: workspace.teamId,
        memberId: ownerMemberId,
      },
    },
  )
  expect(invitation.token).toBeTruthy()

  const authorizationResponse = await context.request.get(
    backend('/api/v1/auth/oidc/authorization/google'),
    { maxRedirects: 0 },
  ).catch(() => {
    throw new Error('OIDC 인가 시작 요청에 실패했습니다.')
  })
  expect(authorizationResponse.status()).toBe(302)
  const sessionCookieBeforeLogin = (await context.cookies())
    .find((cookie) => cookie.name === 'baton_session')
  expect(sessionCookieBeforeLogin).toBeDefined()

  await page.goto(backend('/api/v1/auth/oidc/authorization/google')).catch(() => {
    throw new Error('OIDC 로그인 이동에 실패했습니다.')
  })

  const session = await requireJson<IdentitySession>(
    context.request,
    'get',
    '/api/v1/auth/session',
  )
  expect({
    authenticated: session.authenticated,
    oidcEnabled: session.oidcEnabled,
  }).toEqual({
    authenticated: true,
    oidcEnabled: true,
  })
  expect(session.accountId).toBeTruthy()
  expect(session.csrfHeaderName).toBe('X-CSRF-TOKEN')
  expect(session.csrfToken).toBeTruthy()
  const sessionCookieAfterLogin = (await context.cookies())
    .find((cookie) => cookie.name === 'baton_session')
  expect(sessionCookieAfterLogin).toBeDefined()
  expect({
    domain: sessionCookieAfterLogin?.domain,
    httpOnly: sessionCookieAfterLogin?.httpOnly,
    path: sessionCookieAfterLogin?.path,
    sameSite: sessionCookieAfterLogin?.sameSite,
    secure: sessionCookieAfterLogin?.secure,
  }).toEqual({
    domain: 'localhost',
    httpOnly: true,
    path: '/',
    sameSite: 'Lax',
    secure: false,
  })
  expect(sessionCookieAfterLogin?.value !== sessionCookieBeforeLogin?.value)
    .toBe(true)
  expect((await page.evaluate(() => document.cookie)).includes('baton_session='))
    .toBe(false)

  const csrfHeaders = {
    [session.csrfHeaderName!]: session.csrfToken!,
  }
  const acceptedInvitation = await requireJson<{
    accountId: string
    memberId: string
    role: string
  }>(context.request, 'post', '/api/v1/identity/invitations/accept', {
    headers: csrfHeaders,
    data: { token: invitation.token },
  })
  expect({
    accountMatches: acceptedInvitation.accountId === session.accountId,
    memberMatches: acceptedInvitation.memberId === ownerMemberId,
    role: acceptedInvitation.role,
  }).toEqual({
    accountMatches: true,
    memberMatches: true,
    role: 'OWNER',
  })

  const membership = await requireJson<{
    accountId: string
    memberId: string
    role: string
  }>(
    context.request,
    'get',
    `/api/v1/teams/${workspace.teamId}/membership`,
  )
  expect({
    accountMatches: membership.accountId === session.accountId,
    memberMatches: membership.memberId === ownerMemberId,
    role: membership.role,
  }).toEqual({
    accountMatches: true,
    memberMatches: true,
    role: 'OWNER',
  })

  const role = await requireJson<Role>(
    context.request,
    'post',
    `/api/v1/teams/${workspace.teamId}/seasons/${workspace.seasonId}/roles`,
    {
      headers: {
        ...csrfHeaders,
        'Idempotency-Key': randomUUID(),
      },
      data: {
        name: 'ROUND 진행자',
        purpose: '실제 스터디 방 입장 권한을 관리합니다.',
        currentMemberId: ownerMemberId,
        nextMemberId: null,
        assignmentStartDate: '2026-07-01',
        assignmentEndDate: '2026-12-31',
        responsibilities: ['스터디 방 운영'],
        risk: null,
      },
    },
  )
  const resource = await requireJson<RoleResource>(
    context.request,
    'post',
    `/api/v1/teams/${workspace.teamId}/seasons/${workspace.seasonId}/role-resources`,
    {
      headers: {
        ...csrfHeaders,
        'Idempotency-Key': randomUUID(),
      },
      data: {
        roleId: role.id,
        title: 'ROUND 파일럿 방',
        url: backend(`/room/${roomId}`),
        description: 'OIDC와 참여권 갱신을 검증하는 로컬 방',
      },
    },
  )

  await page.goto(backend('/api/v1/system/status'))
  const locator = {
    teamId: workspace.teamId,
    seasonId: workspace.seasonId,
    resourceId: resource.id,
  }
  const refreshPath = `/round/rooms/${roomId}/participation-grant/refresh`

  const missingCsrfStatus = await page.evaluate(
    async ({ path, body }) => (await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })).status,
    { path: refreshPath, body: locator },
  )
  expect(missingCsrfStatus).toBe(403)
  expect((await context.cookies())
    .some((cookie) => cookie.name === '__Secure-round_access')).toBe(false)

  const wrongCsrfStatus = await page.evaluate(
    async ({ path, body, csrfHeaderName }) => (await fetch(path, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'Content-Type': 'application/json',
        [csrfHeaderName]: 'invalid-csrf-token',
      },
      body: JSON.stringify(body),
    })).status,
    {
      path: refreshPath,
      body: locator,
      csrfHeaderName: session.csrfHeaderName!,
    },
  )
  expect(wrongCsrfStatus).toBe(403)
  expect((await context.cookies())
    .some((cookie) => cookie.name === '__Secure-round_access')).toBe(false)

  const wrongOriginResponse = await context.request.post(backend(refreshPath), {
    headers: {
      ...csrfHeaders,
      Origin: 'https://cross-origin.invalid',
      'Sec-Fetch-Site': 'cross-site',
    },
    data: locator,
  }).catch(() => {
    throw new Error('교차 출처 참여권 갱신 요청에 실패했습니다.')
  })
  expect(wrongOriginResponse.status()).toBe(403)
  expect('set-cookie' in wrongOriginResponse.headers()).toBe(false)
  expect((await context.cookies())
    .some((cookie) => cookie.name === '__Secure-round_access')).toBe(false)

  const refresh = async (): Promise<RefreshResult> => page.evaluate(
    async ({ path, body, csrfHeaderName, csrfToken }) => {
      const response = await fetch(path, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          [csrfHeaderName]: csrfToken,
        },
        body: JSON.stringify(body),
      })
      return {
        status: response.status,
        cacheControl: response.headers.get('cache-control'),
        body: await response.json() as Record<string, unknown>,
      }
    },
    {
      path: refreshPath,
      body: locator,
      csrfHeaderName: session.csrfHeaderName!,
      csrfToken: session.csrfToken!,
    },
  ).catch(() => {
    throw new Error('ROUND 참여권 갱신 브라우저 요청에 실패했습니다.')
  })

  const firstRefresh = await refresh()
  expect(firstRefresh.status).toBe(200)
  expect(firstRefresh.cacheControl).toContain('no-store')
  expect(Object.keys(firstRefresh.body).sort()).toEqual([
    'expiresAt',
    'refreshAfterSeconds',
  ])
  expect(firstRefresh.body.refreshAfterSeconds).toBe(240)
  expect(Object.hasOwn(firstRefresh.body, 'token')).toBe(false)
  const expiresAt = firstRefresh.body.expiresAt
  expect(typeof expiresAt).toBe('number')
  expect(Number.isInteger(expiresAt)).toBe(true)
  expect((expiresAt as number) * 1000).toBeGreaterThan(Date.now())

  const firstGrantCookie = (await context.cookies())
    .find((cookie) => cookie.name === '__Secure-round_access')
  expect(firstGrantCookie).toBeDefined()
  expect({
    httpOnly: firstGrantCookie?.httpOnly,
    secure: firstGrantCookie?.secure,
    sameSite: firstGrantCookie?.sameSite,
    path: firstGrantCookie?.path,
  }).toEqual({
    httpOnly: true,
    secure: true,
    sameSite: 'Strict',
    path: `/round/rooms/${roomId}`,
  })
  expect(firstGrantCookie?.value).toBeTruthy()
  await page.evaluate((path) => {
    window.history.replaceState(null, '', path)
  }, refreshPath)
  expect((await page.evaluate(() => document.cookie))
    .includes('__Secure-round_access=')).toBe(false)
  expect(Math.abs((firstGrantCookie?.expires ?? 0) - (expiresAt as number)) <= 2)
    .toBe(true)

  const jwkSet = await requireJson<RoundJwkSet>(
    request,
    'get',
    '/.well-known/jwks.json',
  )
  expect(jwkSet.keys.length).toBe(1)
  expect({
    alg: jwkSet.keys[0]?.alg,
    kid: jwkSet.keys[0]?.kid,
    kty: jwkSet.keys[0]?.kty,
    use: jwkSet.keys[0]?.use,
  }).toEqual({
    alg: 'RS256',
    kid: 'baton-round-fullstack-e2e',
    kty: 'RSA',
    use: 'sig',
  })
  for (const privateParameter of ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth', 'k']) {
    expect(privateParameter in jwkSet.keys[0]!).toBe(false)
  }
  const verifiedGrant = verifyRoundGrant(firstGrantCookie!.value, jwkSet)
  expect({
    alg: verifiedGrant.header.alg,
    typ: verifiedGrant.header.typ,
  }).toEqual({
    alg: 'RS256',
    typ: 'JWT',
  })
  expect(verifiedGrant.signatureValid).toBe(true)
  expect({
    issuerMatches: verifiedGrant.claims.iss === backendBaseURL,
    aud: verifiedGrant.claims.aud,
    subjectMatches: verifiedGrant.claims.sub === session.accountId,
    roomMatches: verifiedGrant.claims.room_id === roomId,
    studyMatches: verifiedGrant.claims.study_id === workspace.seasonId,
    role: verifiedGrant.claims.role,
    expiryMatches: verifiedGrant.claims.exp === firstRefresh.body.expiresAt,
  }).toEqual({
    issuerMatches: true,
    aud: 'round',
    subjectMatches: true,
    roomMatches: true,
    studyMatches: true,
    role: 'participant',
    expiryMatches: true,
  })
  expect(verifiedGrant.claims.jti).toBeTruthy()
  expect(verifiedGrant.claims.iat).toBeLessThan(verifiedGrant.claims.exp)
  expect(verifiedGrant.claims.exp - verifiedGrant.claims.iat).toBeLessThanOrEqual(300)

  const secondRefresh = await refresh()
  expect(secondRefresh.status).toBe(200)
  const secondGrantCookie = (await context.cookies())
    .find((cookie) => cookie.name === '__Secure-round_access')
  expect(secondGrantCookie?.value).toBeTruthy()
  const secondVerifiedGrant = verifyRoundGrant(secondGrantCookie!.value, jwkSet)
  expect(secondVerifiedGrant.signatureValid).toBe(true)
  expect(secondVerifiedGrant.claims.jti !== verifiedGrant.claims.jti).toBe(true)
})
