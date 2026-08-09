import {
  createPublicKey,
  randomUUID,
  type JsonWebKey as NodeJsonWebKey,
  verify as verifySignature,
} from 'node:crypto'

import { expect, test, type Page } from '@playwright/test'

import { requireLoopbackHttpOrigin } from '../support/loopback-url'

const frontendOrigin = requireLoopbackHttpOrigin(
  process.env.BATON_FULLSTACK_BASE_URL,
  'BATON_FULLSTACK_BASE_URL',
)
const creationKey = requireValue(
  process.env.BATON_FULLSTACK_CREATION_KEY,
  'BATON_FULLSTACK_CREATION_KEY',
)
const accountEmail = requireValue(
  process.env.BATON_FULLSTACK_ACCOUNT_EMAIL,
  'BATON_FULLSTACK_ACCOUNT_EMAIL',
)
const accountPassword = requireValue(
  process.env.BATON_FULLSTACK_ACCOUNT_PASSWORD,
  'BATON_FULLSTACK_ACCOUNT_PASSWORD',
)
const expectedIssuer = requireHttpsOrigin(
  process.env.BATON_FULLSTACK_ROUND_ISSUER,
  'BATON_FULLSTACK_ROUND_ISSUER',
)
const expectedKeyId = requireValue(
  process.env.BATON_FULLSTACK_ROUND_KID,
  'BATON_FULLSTACK_ROUND_KID',
)
const expectedAccountId = '10000000-0000-0000-0000-000000000099'

test.use({ screenshot: 'off', trace: 'off', video: 'off' })

interface BrowserRequestOptions {
  body?: unknown
  form?: Record<string, string>
  headers?: Record<string, string>
}

interface BrowserResponse<T> {
  body: T | null
  cacheControl: string | null
  contentType: string | null
  status: number
}

interface SetCookieMetadata {
  attributes: Record<string, string | true>
  name: string
}

interface CsrfSession {
  csrfHeaderName: string
  csrfToken: string
}

interface AuthenticatedSession extends CsrfSession {
  accountId: string
  authenticated: true
}

interface CreatedWorkspace {
  accessKey: string
  seasonId: string
  teamId: string
}

interface WorkspaceView {
  members: Array<{ id: string; name: string }>
}

interface ClaimedMembership {
  accountId: string
  claimedAt: string
  memberId: string
  teamId: string
}

interface Role {
  id: string
}

interface RoleResource {
  id: string
}

interface RoomMapping {
  createdAt: string
  endedAt: string | null
  resourceId: string
  roomId: string
  seasonId: string
  teamId: string
}

interface ParticipationGrantResponse {
  expiresAt: number
  refreshAfterSeconds: number
}

interface RoundPublicJwk extends NodeJsonWebKey {
  alg: string
  e: string
  kid: string
  kty: 'RSA'
  n: string
  use: 'sig'
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
  [claim: string]: unknown
}

function requireValue(value: string | undefined, variableName: string): string {
  if (!value) {
    throw new Error(`${variableName}가 필요합니다.`)
  }
  return value
}

function requireHttpsOrigin(value: string | undefined, variableName: string): string {
  const candidate = requireValue(value, variableName)
  let url: URL
  try {
    url = new URL(candidate)
  } catch {
    throw new Error(`${variableName}는 HTTPS origin이어야 합니다.`)
  }
  if (
    url.protocol !== 'https:'
    || url.origin !== candidate
    || url.username
    || url.password
    || url.pathname !== '/'
    || url.search
    || url.hash
  ) {
    throw new Error(`${variableName}는 HTTPS origin이어야 합니다.`)
  }
  return url.origin
}

async function browserRequest<T>(
  page: Page,
  method: 'GET' | 'POST',
  path: string,
  options: BrowserRequestOptions = {},
): Promise<BrowserResponse<T>> {
  return page.evaluate(async ({ requestMethod, requestPath, requestOptions }) => {
    const headers = new Headers(requestOptions.headers)
    let body: string | undefined
    if (requestOptions.form) {
      headers.set('Content-Type', 'application/x-www-form-urlencoded')
      body = new URLSearchParams(requestOptions.form).toString()
    } else if (requestOptions.body !== undefined) {
      headers.set('Content-Type', 'application/json')
      body = JSON.stringify(requestOptions.body)
    }
    const response = await fetch(requestPath, {
      method: requestMethod,
      credentials: 'same-origin',
      headers,
      body,
    })
    const text = await response.text()
    let parsedBody: T | null = null
    if (text) {
      try {
        parsedBody = JSON.parse(text) as T
      } catch {
        throw new Error(`HTTP ${response.status} 응답이 JSON 형식이 아닙니다.`)
      }
    }
    return {
      body: parsedBody,
      cacheControl: response.headers.get('cache-control'),
      contentType: response.headers.get('content-type'),
      status: response.status,
    }
  }, {
    requestMethod: method,
    requestOptions: options,
    requestPath: path,
  })
}

function requireBody<T>(response: BrowserResponse<T>, label: string): T {
  expect(response.body, `${label} 본문`).not.toBeNull()
  return response.body as T
}

function parseSetCookieMetadata(header: string | null): SetCookieMetadata {
  if (!header) {
    throw new Error('ROUND 참여권 Set-Cookie header가 없습니다.')
  }
  const [cookiePair, ...attributeSegments] = header.split(';')
  const cookieSeparator = cookiePair?.indexOf('=') ?? -1
  if (!cookiePair || cookieSeparator <= 0) {
    throw new Error('ROUND 참여권 Set-Cookie 형식이 올바르지 않습니다.')
  }

  const attributes: Record<string, string | true> = {}
  for (const segment of attributeSegments) {
    const normalized = segment.trim()
    if (!normalized) {
      continue
    }
    const separator = normalized.indexOf('=')
    if (separator < 0) {
      attributes[normalized.toLowerCase()] = true
      continue
    }
    attributes[normalized.slice(0, separator).toLowerCase()] = normalized.slice(separator + 1)
  }
  return {
    attributes,
    name: cookiePair.slice(0, cookieSeparator),
  }
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
): { claims: RoundGrantClaims; header: RoundGrantHeader; signatureValid: boolean } {
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
  return {
    claims,
    header,
    signatureValid: verifySignature(
      'RSA-SHA256',
      Buffer.from(`${encodedHeader}.${encodedClaims}`),
      createPublicKey({ key: publicJwk, format: 'jwk' }),
      Buffer.from(encodedSignature, 'base64url'),
    ),
  }
}

test('실제 local session과 구성원 claim으로 ROUND 참여권을 발급한다', async ({
  context,
  page,
}) => {
  await page.goto('/')

  const jwkResponse = await browserRequest<RoundJwkSet>(
    page,
    'GET',
    '/.well-known/round-participation-jwks.json',
  )
  expect(jwkResponse.status).toBe(200)
  expect(jwkResponse.contentType).toContain('application/jwk-set+json')
  expect(jwkResponse.cacheControl).toContain('max-age=60')
  expect(jwkResponse.cacheControl).toContain('public')
  const jwkSet = requireBody(jwkResponse, '공개 JWK Set')
  expect(jwkSet.keys).toHaveLength(1)
  const publicJwk = jwkSet.keys[0]
  expect({
    alg: publicJwk?.alg,
    kid: publicJwk?.kid,
    kty: publicJwk?.kty,
    use: publicJwk?.use,
  }).toEqual({
    alg: 'RS256',
    kid: expectedKeyId,
    kty: 'RSA',
    use: 'sig',
  })
  for (const privateParameter of ['d', 'p', 'q', 'dp', 'dq', 'qi', 'oth', 'k']) {
    expect(privateParameter in publicJwk!).toBe(false)
  }

  const csrfResponse = await browserRequest<CsrfSession>(page, 'GET', '/api/v1/auth/csrf')
  expect(csrfResponse.status).toBe(200)
  const loginCsrf = requireBody(csrfResponse, '로그인 CSRF')
  const sessionCookieBeforeLogin = (await context.cookies(frontendOrigin))
    .find((cookie) => cookie.name === 'JSESSIONID')
  expect(sessionCookieBeforeLogin).toBeDefined()

  const loginResponse = await browserRequest<never>(page, 'POST', '/api/v1/auth/local/session', {
    form: {
      email: accountEmail,
      password: accountPassword,
    },
    headers: {
      [loginCsrf.csrfHeaderName]: loginCsrf.csrfToken,
    },
  })
  expect(loginResponse.status).toBe(204)
  expect(loginResponse.cacheControl).toContain('no-store')

  const sessionResponse = await browserRequest<AuthenticatedSession>(
    page,
    'GET',
    '/api/v1/auth/session',
  )
  expect(sessionResponse.status).toBe(200)
  const session = requireBody(sessionResponse, '인증 session')
  expect(session).toMatchObject({
    accountId: expectedAccountId,
    authenticated: true,
    csrfHeaderName: 'X-CSRF-TOKEN',
  })
  expect(session.csrfToken).toBeTruthy()
  const sessionCookieAfterLogin = (await context.cookies(frontendOrigin))
    .find((cookie) => cookie.name === 'JSESSIONID')
  expect(sessionCookieAfterLogin).toBeDefined()
  expect(sessionCookieAfterLogin?.value !== sessionCookieBeforeLogin?.value).toBe(true)

  const year = new Date().getUTCFullYear()
  const startDate = `${year}-01-01`
  const endDate = `${year}-12-31`
  const workspaceResponse = await browserRequest<CreatedWorkspace>(
    page,
    'POST',
    '/api/v1/workspaces',
    {
      body: {
        teamName: `ROUND 풀스택 검증팀 ${year}`,
        seasonName: `${year} 활성 시즌`,
        startDate,
        endDate,
        memberNames: ['ROUND 참여 구성원'],
      },
      headers: {
        'Idempotency-Key': randomUUID(),
        'X-Baton-Creation-Key': creationKey,
      },
    },
  )
  expect(workspaceResponse.status).toBe(201)
  const workspace = requireBody(workspaceResponse, '워크스페이스 생성')

  const workspaceViewResponse = await browserRequest<WorkspaceView>(
    page,
    'GET',
    `/api/v1/teams/${workspace.teamId}/seasons/${workspace.seasonId}/workspace`,
    { headers: { 'X-Baton-Access-Key': workspace.accessKey } },
  )
  expect(workspaceViewResponse.status).toBe(200)
  const member = requireBody(workspaceViewResponse, '워크스페이스 조회').members
    .find((candidate) => candidate.name === 'ROUND 참여 구성원')
  expect(member).toBeDefined()

  const currentMembershipResponse = await browserRequest<{ claimed: boolean }>(
    page,
    'GET',
    `/api/v1/account-memberships/current?teamId=${workspace.teamId}`,
    { headers: { 'X-Baton-Access-Key': workspace.accessKey } },
  )
  expect(currentMembershipResponse.status).toBe(200)
  expect(currentMembershipResponse.body).toEqual({ claimed: false })

  const sessionMutationHeaders = {
    [session.csrfHeaderName]: session.csrfToken,
  }
  const claimResponse = await browserRequest<ClaimedMembership>(
    page,
    'POST',
    '/api/v1/account-membership-claims',
    {
      body: {
        memberId: member!.id,
        seasonId: workspace.seasonId,
        teamId: workspace.teamId,
      },
      headers: {
        ...sessionMutationHeaders,
        'X-Baton-Access-Key': workspace.accessKey,
      },
    },
  )
  expect(claimResponse.status).toBe(200)
  expect(requireBody(claimResponse, 'AccountMembership claim')).toMatchObject({
    accountId: session.accountId,
    memberId: member!.id,
    teamId: workspace.teamId,
  })

  const claimedMembershipResponse = await browserRequest<
    ClaimedMembership & { claimed: true }
  >(
    page,
    'GET',
    `/api/v1/account-memberships/current?teamId=${workspace.teamId}`,
    { headers: { 'X-Baton-Access-Key': workspace.accessKey } },
  )
  expect(claimedMembershipResponse.status).toBe(200)
  expect(requireBody(claimedMembershipResponse, '현재 AccountMembership')).toMatchObject({
    accountId: session.accountId,
    claimed: true,
    memberId: member!.id,
    teamId: workspace.teamId,
  })

  const roleResponse = await browserRequest<Role>(
    page,
    'POST',
    `/api/v1/teams/${workspace.teamId}/seasons/${workspace.seasonId}/roles`,
    {
      body: {
        assignmentEndDate: endDate,
        assignmentStartDate: startDate,
        currentMemberId: member!.id,
        name: 'ROUND 진행자',
        nextMemberId: null,
        purpose: 'authoritative ROUND room 자료를 관리합니다.',
        responsibilities: ['ROUND room 운영'],
        risk: null,
      },
      headers: {
        'Idempotency-Key': randomUUID(),
        'X-Baton-Access-Key': workspace.accessKey,
      },
    },
  )
  expect(roleResponse.status).toBe(201)
  const role = requireBody(roleResponse, '역할 생성')

  const resourceResponse = await browserRequest<RoleResource>(
    page,
    'POST',
    `/api/v1/teams/${workspace.teamId}/seasons/${workspace.seasonId}/role-resources`,
    {
      body: {
        description: '참여권 producer full-stack 검증용 자료',
        roleId: role.id,
        title: 'ROUND 파일럿 room',
        url: 'https://round.example.test/rooms/fullstack',
      },
      headers: {
        'Idempotency-Key': randomUUID(),
        'X-Baton-Access-Key': workspace.accessKey,
      },
    },
  )
  expect(resourceResponse.status).toBe(201)
  const resource = requireBody(resourceResponse, '역할 자료 생성')

  const mappingResponse = await browserRequest<RoomMapping>(
    page,
    'POST',
    '/api/v1/round-room-mappings',
    {
      body: {
        resourceId: resource.id,
        seasonId: workspace.seasonId,
        teamId: workspace.teamId,
      },
      headers: {
        ...sessionMutationHeaders,
        'X-Baton-Access-Key': workspace.accessKey,
      },
    },
  )
  expect(mappingResponse.status).toBe(200)
  const mapping = requireBody(mappingResponse, 'ROUND room mapping 생성')
  expect(mapping).toMatchObject({
    endedAt: null,
    resourceId: resource.id,
    seasonId: workspace.seasonId,
    teamId: workspace.teamId,
  })
  expect(mapping.roomId).toMatch(
    /^[abcdefghjkmnpqrstuvwxyz23456789]{4}(?:-[abcdefghjkmnpqrstuvwxyz23456789]{4}){2}$/,
  )

  const refreshPath = `/round/rooms/${mapping.roomId}/participation-grant/refresh`
  const refreshNetworkResponse = page.waitForResponse((response) => (
    response.request().method() === 'POST'
    && new URL(response.url()).pathname === refreshPath
  ))
  const refreshResponse = await browserRequest<ParticipationGrantResponse>(
    page,
    'POST',
    refreshPath,
    {
      body: {
        resourceId: resource.id,
        seasonId: workspace.seasonId,
        teamId: workspace.teamId,
      },
      headers: sessionMutationHeaders,
    },
  )
  const observedRefreshResponse = await refreshNetworkResponse
  expect(refreshResponse.status).toBe(200)
  expect(refreshResponse.cacheControl).toContain('no-store')
  const refreshBody = requireBody(refreshResponse, 'ROUND 참여권 갱신')
  expect(Object.keys(refreshBody).sort()).toEqual(['expiresAt', 'refreshAfterSeconds'])
  expect(refreshBody.refreshAfterSeconds).toBe(240)
  expect(Object.hasOwn(refreshBody, 'token')).toBe(false)

  const setCookie = parseSetCookieMetadata(
    await observedRefreshResponse.headerValue('set-cookie'),
  )
  expect(setCookie.name).toBe('__Secure-round_access')
  expect(setCookie.attributes).toMatchObject({
    httponly: true,
    path: `/round/rooms/${mapping.roomId}`,
    samesite: 'Strict',
    secure: true,
  })
  expect(Object.hasOwn(setCookie.attributes, 'domain')).toBe(false)

  const firstGrantCookie = (await context.cookies())
    .find((cookie) => cookie.name === '__Secure-round_access')
  expect(firstGrantCookie).toBeDefined()
  expect({
    domain: firstGrantCookie?.domain,
    httpOnly: firstGrantCookie?.httpOnly,
    path: firstGrantCookie?.path,
    sameSite: firstGrantCookie?.sameSite,
    secure: firstGrantCookie?.secure,
  }).toEqual({
    domain: new URL(frontendOrigin).hostname,
    httpOnly: true,
    path: `/round/rooms/${mapping.roomId}`,
    sameSite: 'Strict',
    secure: true,
  })
  expect(Math.abs((firstGrantCookie?.expires ?? 0) - refreshBody.expiresAt)).toBeLessThanOrEqual(2)
  await page.evaluate((path) => window.history.replaceState(null, '', path), refreshPath)
  expect((await page.evaluate(() => document.cookie)).includes('__Secure-round_access=')).toBe(false)

  const verifiedGrant = verifyRoundGrant(firstGrantCookie!.value, jwkSet)
  expect(verifiedGrant.signatureValid).toBe(true)
  expect(verifiedGrant.header).toEqual({
    alg: 'RS256',
    kid: expectedKeyId,
    typ: 'JWT',
  })
  expect(verifiedGrant.claims).toMatchObject({
    aud: 'round',
    exp: refreshBody.expiresAt,
    iss: expectedIssuer,
    role: 'participant',
    room_id: mapping.roomId,
    study_id: workspace.teamId,
    sub: session.accountId,
  })
  expect(Object.hasOwn(verifiedGrant.claims, 'nbf')).toBe(false)
  expect(verifiedGrant.claims.jti).toMatch(
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  )
  expect(verifiedGrant.claims.exp - verifiedGrant.claims.iat).toBe(300)

  const secondRefreshResponse = await browserRequest<ParticipationGrantResponse>(
    page,
    'POST',
    refreshPath,
    {
      body: {
        resourceId: resource.id,
        seasonId: workspace.seasonId,
        teamId: workspace.teamId,
      },
      headers: sessionMutationHeaders,
    },
  )
  expect(secondRefreshResponse.status).toBe(200)
  const secondGrantCookie = (await context.cookies())
    .find((cookie) => cookie.name === '__Secure-round_access')
  expect(secondGrantCookie).toBeDefined()
  const secondGrant = verifyRoundGrant(secondGrantCookie!.value, jwkSet)
  expect(secondGrant.signatureValid).toBe(true)
  expect(secondGrant.claims.jti !== verifiedGrant.claims.jti).toBe(true)
})
