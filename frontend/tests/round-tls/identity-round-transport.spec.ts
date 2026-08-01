import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import type { Response as PlaywrightResponse } from '@playwright/test'

import { requireBatonLocalhostHttpsOrigin } from '../support/loopback-url'

const edgeBaseURL = requireBatonLocalhostHttpsOrigin(
  process.env.BATON_ROUND_EDGE_BASE_URL,
  'BATON_ROUND_EDGE_BASE_URL',
)
const roomId = 'abcd-efgh-jkmn'
const canonicalUuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

type FixtureLocators = Readonly<{
  teamId: string
  seasonId: string
  resourceId: string
}>

type TransportEvent =
  | Readonly<{ kind: 'grant' | 'turn'; status: number }>
  | Readonly<{ kind: 'wss'; roomScoped: boolean; secure: boolean }>

type TransportObserver = {
  protectedRequestCount: number
  standaloneRequestCount: number
  events: TransportEvent[]
}

type SafeStage =
  | 'oidc-login'
  | 'closed-routes'
  | 'fixture-created'
  | 'entry-context'
  | 'prejoin'
  | 'media-probe'
  | 'room-connected'
  | 'media-less'
  | 'protected-transport'
  | 'cookie-metadata'
  | 'cookie-visibility'

function markSafeStage(stage: SafeStage): void {
  console.log(`[round-tls-stage] ${stage}`)
}

function markSafeTransport(observer: TransportObserver): void {
  const grantStatuses = observer.events
    .filter((event): event is Readonly<{ kind: 'grant'; status: number }> =>
      event.kind === 'grant')
    .map((event) => event.status)
  const turnStatuses = observer.events
    .filter((event): event is Readonly<{ kind: 'turn'; status: number }> =>
      event.kind === 'turn')
    .map((event) => event.status)
  const webSocketCount = observer.events.filter((event) => event.kind === 'wss').length
  console.log(
    `[round-tls-transport] protected=${observer.protectedRequestCount}`
    + ` standalone=${observer.standaloneRequestCount}`
    + ` grant=${grantStatuses.join(',') || 'none'}`
    + ` turn=${turnStatuses.join(',') || 'none'}`
    + ` wss=${webSocketCount}`,
  )
}

async function readSafeResponseMetadata(response: PlaywrightResponse): Promise<Readonly<{
  content: 'absent' | 'json' | 'other'
  length: 'absent' | 'zero' | 'positive' | 'invalid'
}>> {
  const headers = await response.allHeaders()
  const contentType = headers['content-type']
  const content = contentType === undefined
    ? 'absent'
    : contentType.toLowerCase().startsWith('application/json')
      ? 'json'
      : 'other'
  const rawLength = headers['content-length']
  const length = rawLength === undefined
    ? 'absent'
    : rawLength === '0'
      ? 'zero'
      : /^[1-9][0-9]*$/.test(rawLength)
        ? 'positive'
        : 'invalid'
  return { content, length }
}

async function readSafeGrantRequest(response: PlaywrightResponse): Promise<Readonly<{
  body: 'empty' | 'present'
  content: 'json' | 'other'
  cookieShape: 'valid' | 'invalid'
  cookies: number
  csrf: 'present' | 'absent'
  fetchSite: 'same-origin' | 'other' | 'absent'
  grant: number
  method: string
  origin: 'match' | 'mismatch' | 'absent'
  other: number
  query: 'absent' | 'present'
  session: number
}>> {
  const request = response.request()
  const headers = await request.allHeaders()
  const cookieNames = (headers.cookie ?? '')
    .split(';')
    .map((part) => part.trim().split('=', 1)[0] ?? '')
    .filter((name) => name.length > 0)
  const sessionCount = cookieNames.filter((name) => name === '__Host-baton_session').length
  const grantCount = cookieNames.filter((name) => name === '__Secure-round_access').length
  const otherCount = cookieNames.length - sessionCount - grantCount
  const target = new URL(request.url())
  const requestBody = request.postData()
  const cookieShape = /^__Host-baton_session=[^;,\s]+$/.test(headers.cookie ?? '')
  return {
    body: requestBody === null || requestBody.length === 0 ? 'empty' : 'present',
    content: headers['content-type']?.toLowerCase().startsWith('application/json')
      ? 'json'
      : 'other',
    cookieShape: cookieShape ? 'valid' : 'invalid',
    cookies: cookieNames.length,
    csrf: headers['x-csrf-token'] ? 'present' : 'absent',
    fetchSite: headers['sec-fetch-site'] === 'same-origin'
      ? 'same-origin'
      : headers['sec-fetch-site']
        ? 'other'
        : 'absent',
    grant: grantCount,
    method: request.method(),
    origin: headers.origin === edgeBaseURL ? 'match' : headers.origin ? 'mismatch' : 'absent',
    other: otherCount,
    query: target.search === '' ? 'absent' : 'present',
    session: sessionCount,
  }
}

function markSafeGrantRequest(metadata: Awaited<ReturnType<typeof readSafeGrantRequest>>): void {
  console.log(
    `[round-tls-request] method=${metadata.method}`
    + ` query=${metadata.query}`
    + ` cookies=${metadata.cookies}`
    + ` session=${metadata.session}`
    + ` grant=${metadata.grant}`
    + ` other=${metadata.other}`
    + ` cookieShape=${metadata.cookieShape}`
    + ` origin=${metadata.origin}`
    + ` fetchSite=${metadata.fetchSite}`
    + ` csrf=${metadata.csrf}`
    + ` content=${metadata.content}`
    + ` body=${metadata.body}`,
  )
}

function requiredUuid(value: unknown, label: string): string {
  if (typeof value !== 'string' || !canonicalUuidPattern.test(value)) {
    throw new Error(`${label} 식별자를 확인하지 못했습니다.`)
  }
  return value
}

async function verifyClosedEdgeRoutes(context: BrowserContext): Promise<void> {
  const probes = [
    { label: 'standalone-signal', method: 'GET', path: '/signal' },
    { label: 'standalone-turn', method: 'POST', path: '/api/turn-credentials' },
    {
      label: 'room-query',
      method: 'GET',
      path: `/room/${roomId}?unexpected=1`,
    },
    {
      label: 'refresh-query',
      method: 'POST',
      path: `/round/rooms/${roomId}/participation-grant/refresh?unexpected=1`,
    },
  ] as const
  const responses = await Promise.all(probes.map(({ method, path }) =>
    context.request.fetch(`${edgeBaseURL}${path}`, {
      data: method === 'POST' ? {} : undefined,
      failOnStatusCode: false,
      method,
    })))
  try {
    expect(Object.fromEntries(responses.map((response, index) => [
      probes[index]?.label ?? 'unknown',
      response.status(),
    ]))).toEqual({
      'refresh-query': 404,
      'room-query': 404,
      'standalone-signal': 404,
      'standalone-turn': 404,
    })
  } finally {
    await Promise.all(responses.map((response) => response.dispose()))
  }
}

async function logInWithMockOidc(page: Page): Promise<void> {
  await page.goto('/api/v1/auth/oidc/authorization/google')
  const ownerChoice = page.getByRole('button', { name: 'owner 계정으로 계속' })
  if (await ownerChoice.count() === 1) {
    await ownerChoice.click()
  }

  await expect.poll(async () => page.evaluate((expectedOrigin) => {
    const current = new URL(window.location.href)
    return {
      callbackCredentialAbsent:
        !current.searchParams.has('code') && !current.searchParams.has('state'),
      originMatches: current.origin === expectedOrigin,
    }
  }, edgeBaseURL)).toEqual({
    callbackCredentialAbsent: true,
    originMatches: true,
  })
}

async function createRoundFixture(page: Page): Promise<FixtureLocators> {
  const setup = await page.evaluate(async ({ targetRoomId }) => {
    const uuidPattern =
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    const asRecord = (value: unknown): Record<string, unknown> | null =>
      typeof value === 'object' && value !== null && !Array.isArray(value)
        ? value as Record<string, unknown>
        : null
    const readRecord = async (response: Response): Promise<Record<string, unknown> | null> => {
      try {
        return asRecord(await response.json())
      } catch {
        return null
      }
    }
    const metadata = {
      sessionStatus: null as number | null,
      authenticated: false,
      oidcEnabled: false,
      csrfReady: false,
      workspaceStatus: null as number | null,
      workspaceIdsValid: false,
      workspaceHasAccessKey: false,
      workspaceLookupStatus: null as number | null,
      ownerMemberFound: false,
      membershipStatus: null as number | null,
      membershipIsOwner: false,
      membershipMatches: false,
      roleStatus: null as number | null,
      roleIdValid: false,
      resourceStatus: null as number | null,
      resourceIdValid: false,
    }
    const locators = {
      teamId: null as string | null,
      seasonId: null as string | null,
      resourceId: null as string | null,
    }

    const sessionResponse = await fetch('/api/v1/auth/session', {
      cache: 'no-store',
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
    metadata.sessionStatus = sessionResponse.status
    const session = await readRecord(sessionResponse)
    metadata.authenticated = session?.authenticated === true
    metadata.oidcEnabled = session?.oidcEnabled === true
    const accountId = session?.accountId
    const csrfHeaderName = session?.csrfHeaderName
    const csrfToken = session?.csrfToken
    metadata.csrfReady =
      typeof accountId === 'string'
      && uuidPattern.test(accountId)
      && typeof csrfHeaderName === 'string'
      && csrfHeaderName === 'X-CSRF-TOKEN'
      && typeof csrfToken === 'string'
      && csrfToken.length > 0
    if (
      !metadata.csrfReady
      || typeof csrfHeaderName !== 'string'
      || typeof csrfToken !== 'string'
    ) {
      return { locators, metadata }
    }

    const mutationHeaders = () => ({
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID(),
      [csrfHeaderName]: csrfToken,
    })
    const workspaceResponse = await fetch('/api/v1/me/workspaces', {
      method: 'POST',
      cache: 'no-store',
      credentials: 'same-origin',
      headers: mutationHeaders(),
      body: JSON.stringify({
        teamName: 'ROUND TLS 검증팀',
        seasonName: '로컬 HTTPS 시즌',
        startDate: '2026-08-01',
        endDate: '2026-12-31',
        memberNames: ['TLS 소유자'],
        ownerMemberName: 'TLS 소유자',
      }),
    })
    metadata.workspaceStatus = workspaceResponse.status
    const workspace = await readRecord(workspaceResponse)
    const teamId = workspace?.teamId
    const seasonId = workspace?.seasonId
    metadata.workspaceIdsValid =
      typeof teamId === 'string'
      && uuidPattern.test(teamId)
      && typeof seasonId === 'string'
      && uuidPattern.test(seasonId)
    metadata.workspaceHasAccessKey = workspace !== null && Object.hasOwn(workspace, 'accessKey')
    if (!metadata.workspaceIdsValid || typeof teamId !== 'string' || typeof seasonId !== 'string') {
      return { locators, metadata }
    }
    locators.teamId = teamId
    locators.seasonId = seasonId

    const workspaceLookupResponse = await fetch(
      `/api/v1/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}/workspace`,
      {
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      },
    )
    metadata.workspaceLookupStatus = workspaceLookupResponse.status
    const workspaceView = await readRecord(workspaceLookupResponse)
    const members = Array.isArray(workspaceView?.members) ? workspaceView.members : []
    const ownerMember = members
      .map(asRecord)
      .find((member) => member?.name === 'TLS 소유자') ?? null
    const ownerMemberId = ownerMember?.id
    metadata.ownerMemberFound =
      typeof ownerMemberId === 'string' && uuidPattern.test(ownerMemberId)
    if (!metadata.ownerMemberFound || typeof ownerMemberId !== 'string') {
      return { locators, metadata }
    }

    const membershipResponse = await fetch(
      `/api/v1/teams/${encodeURIComponent(teamId)}/membership`,
      {
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { Accept: 'application/json' },
      },
    )
    metadata.membershipStatus = membershipResponse.status
    const membership = await readRecord(membershipResponse)
    metadata.membershipIsOwner = membership?.role === 'OWNER'
    metadata.membershipMatches =
      membership?.accountId === accountId && membership?.memberId === ownerMemberId

    const roleResponse = await fetch(
      `/api/v1/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}/roles`,
      {
        method: 'POST',
        cache: 'no-store',
        credentials: 'same-origin',
        headers: mutationHeaders(),
        body: JSON.stringify({
          name: 'ROUND 진행자',
          purpose: '로컬 HTTPS 전송 경계를 검증합니다.',
          currentMemberId: ownerMemberId,
          nextMemberId: null,
          assignmentStartDate: '2026-08-01',
          assignmentEndDate: '2026-12-31',
          responsibilities: ['ROUND 입장 확인'],
          risk: null,
        }),
      },
    )
    metadata.roleStatus = roleResponse.status
    const role = await readRecord(roleResponse)
    const roleId = role?.id
    metadata.roleIdValid = typeof roleId === 'string' && uuidPattern.test(roleId)
    if (!metadata.roleIdValid || typeof roleId !== 'string') {
      return { locators, metadata }
    }

    const resourceResponse = await fetch(
      `/api/v1/teams/${encodeURIComponent(teamId)}/seasons/${encodeURIComponent(seasonId)}/role-resources`,
      {
        method: 'POST',
        cache: 'no-store',
        credentials: 'same-origin',
        headers: mutationHeaders(),
        body: JSON.stringify({
          roleId,
          title: 'ROUND TLS 방',
          url: `${window.location.origin}/room/${targetRoomId}`,
          description: 'Caddy TLS와 ROUND 보호 전송을 검증합니다.',
        }),
      },
    )
    metadata.resourceStatus = resourceResponse.status
    const resource = await readRecord(resourceResponse)
    const resourceId = resource?.id
    metadata.resourceIdValid =
      typeof resourceId === 'string' && uuidPattern.test(resourceId)
    if (metadata.resourceIdValid && typeof resourceId === 'string') {
      locators.resourceId = resourceId
    }
    return { locators, metadata }
  }, { targetRoomId: roomId })

  expect(setup.metadata).toEqual({
    sessionStatus: 200,
    authenticated: true,
    oidcEnabled: true,
    csrfReady: true,
    workspaceStatus: 201,
    workspaceIdsValid: true,
    workspaceHasAccessKey: false,
    workspaceLookupStatus: 200,
    ownerMemberFound: true,
    membershipStatus: 200,
    membershipIsOwner: true,
    membershipMatches: true,
    roleStatus: 201,
    roleIdValid: true,
    resourceStatus: 201,
    resourceIdValid: true,
  })

  return {
    teamId: requiredUuid(setup.locators.teamId, '팀'),
    seasonId: requiredUuid(setup.locators.seasonId, '시즌'),
    resourceId: requiredUuid(setup.locators.resourceId, '자료'),
  }
}

function observeProtectedTransport(page: Page): TransportObserver {
  const roomPath = `/round/rooms/${roomId}`
  const grantPath = `${roomPath}/participation-grant/refresh`
  const turnPath = `${roomPath}/turn-credentials`
  const signalPath = `${roomPath}/signal`
  const observer: TransportObserver = {
    protectedRequestCount: 0,
    standaloneRequestCount: 0,
    events: [],
  }

  page.on('request', (request) => {
    const target = new URL(request.url())
    if (target.origin !== edgeBaseURL) {
      return
    }
    if (target.pathname === grantPath || target.pathname === turnPath) {
      observer.protectedRequestCount += 1
    }
    if (
      target.pathname === '/api/turn-credentials'
      || target.pathname === '/signal'
      || /^\/api\/v1\/round\/rooms\/[^/]+\/participation-grant$/.test(target.pathname)
      || /\/role-resources\/[^/]+\/round-participation-grant$/.test(target.pathname)
    ) {
      observer.standaloneRequestCount += 1
    }
  })

  page.on('response', (response) => {
    const target = new URL(response.url())
    if (target.origin !== edgeBaseURL) {
      return
    }
    if (target.pathname === grantPath) {
      observer.events.push({ kind: 'grant', status: response.status() })
    }
    if (target.pathname === turnPath) {
      observer.events.push({ kind: 'turn', status: response.status() })
    }
  })

  page.on('websocket', (webSocket) => {
    const target = new URL(webSocket.url())
    if (target.pathname === signalPath) {
      observer.protectedRequestCount += 1
      observer.events.push({
        kind: 'wss',
        roomScoped: true,
        secure: target.protocol === 'wss:' && target.host === new URL(edgeBaseURL).host,
      })
      return
    }
    if (target.pathname === '/signal') {
      observer.standaloneRequestCount += 1
    }
  })

  return observer
}

test('OIDC OWNER가 TLS edge에서 참여권 다음 TURN과 WSS 순서로 입장한다', async ({
  context,
  page,
}) => {
  await logInWithMockOidc(page)
  markSafeStage('oidc-login')
  await verifyClosedEdgeRoutes(context)
  markSafeStage('closed-routes')
  const fixture = await createRoundFixture(page)
  markSafeStage('fixture-created')
  const transport = observeProtectedTransport(page)

  await page.goto(`/room/${roomId}`)
  const entryMetadata = await page.evaluate(({ targetRoomId, locators }) => {
    const storageKey = `baton-round-entry:v1:${targetRoomId}`
    const serialized = JSON.stringify({
      version: 1,
      teamId: locators.teamId,
      seasonId: locators.seasonId,
      resourceId: locators.resourceId,
      roomId: targetRoomId,
    })
    sessionStorage.setItem(storageKey, serialized)
    const stored = sessionStorage.getItem(storageKey)
    return {
      exactFieldCount: stored === null ? 0 : Object.keys(JSON.parse(stored)).length,
      hasCredentialField: stored === null
        || /accessKey|authorization|cookie|csrf|token/i.test(stored),
      stored: stored === serialized,
    }
  }, { targetRoomId: roomId, locators: fixture })
  expect(entryMetadata).toEqual({
    exactFieldCount: 5,
    hasCredentialField: false,
    stored: true,
  })
  markSafeStage('entry-context')

  await page.getByLabel('내 이름').fill('TLS 소유자')
  await page.getByRole('button', { name: '입장 준비' }).click()
  await expect(page.getByRole('heading', {
    name: '입장 전에 장치를 확인해 주세요.',
  })).toBeVisible()
  expect({
    protectedRequestCount: transport.protectedRequestCount,
    standaloneRequestCount: transport.standaloneRequestCount,
    transportEventCount: transport.events.length,
  }).toEqual({
    protectedRequestCount: 0,
    standaloneRequestCount: 0,
    transportEventCount: 0,
  })
  markSafeStage('prejoin')

  const mediaProbeInstalled = await page.evaluate(() => {
    const target = window as Window & { __batonRoundTlsMediaRequestCount?: number }
    target.__batonRoundTlsMediaRequestCount = 0
    if (typeof navigator.mediaDevices?.getUserMedia !== 'function') {
      return false
    }
    const originalGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices)
    try {
      navigator.mediaDevices.getUserMedia = (...constraints) => {
        target.__batonRoundTlsMediaRequestCount =
          (target.__batonRoundTlsMediaRequestCount ?? 0) + 1
        return originalGetUserMedia(...constraints)
      }
      return true
    } catch {
      return false
    }
  })
  expect(mediaProbeInstalled).toBe(true)
  markSafeStage('media-probe')

  const initialGrantResponsePromise = page.waitForResponse((response) => {
    const target = new URL(response.url())
    return target.origin === edgeBaseURL
      && target.pathname === `/round/rooms/${roomId}/participation-grant/refresh`
  }, { timeout: 15_000 })
  let initialGrantResponse: PlaywrightResponse
  try {
    await page.getByRole('button', { name: '미디어 없이 입장' }).click()
    initialGrantResponse = await initialGrantResponsePromise
  } catch (error) {
    markSafeTransport(transport)
    throw error
  }
  const safeGrantRequest = await readSafeGrantRequest(initialGrantResponse)
  markSafeGrantRequest(safeGrantRequest)
  const safeResponse = await readSafeResponseMetadata(initialGrantResponse)
  console.log(
    `[round-tls-response] grant status=${initialGrantResponse.status()}`
    + ` content=${safeResponse.content}`
    + ` length=${safeResponse.length}`,
  )
  expect({
    ...safeGrantRequest,
    status: initialGrantResponse.status(),
  }).toEqual({
    body: 'present',
    content: 'json',
    cookieShape: 'valid',
    cookies: 1,
    csrf: 'present',
    fetchSite: 'same-origin',
    grant: 0,
    method: 'POST',
    origin: 'match',
    other: 0,
    query: 'absent',
    session: 1,
    status: 200,
  })
  try {
    await expect(page.locator('.room-header__status .connection-state')).toHaveText(
      '입장 완료 · 대기 중',
      { timeout: 30_000 },
    )
  } catch (error) {
    markSafeTransport(transport)
    throw error
  }
  markSafeStage('room-connected')
  markSafeTransport(transport)

  const mediaRequestCount = await page.evaluate(() =>
    (window as Window & { __batonRoundTlsMediaRequestCount?: number })
      .__batonRoundTlsMediaRequestCount ?? -1)
  expect(mediaRequestCount).toBe(0)
  markSafeStage('media-less')

  const firstGrantIndex = transport.events.findIndex((event) => event.kind === 'grant')
  const firstTurnIndex = transport.events.findIndex((event) => event.kind === 'turn')
  const firstWebSocketIndex = transport.events.findIndex((event) => event.kind === 'wss')
  const grantEvent = transport.events[firstGrantIndex]
  const turnEvent = transport.events[firstTurnIndex]
  const webSocketEvent = transport.events[firstWebSocketIndex]
  expect({
    grantStatus: grantEvent?.kind === 'grant' ? grantEvent.status : null,
    turnStatus: turnEvent?.kind === 'turn' ? turnEvent.status : null,
    wssRoomScoped: webSocketEvent?.kind === 'wss' && webSocketEvent.roomScoped,
    wssSecure: webSocketEvent?.kind === 'wss' && webSocketEvent.secure,
    strictOrder:
      firstGrantIndex >= 0
      && firstGrantIndex < firstTurnIndex
      && firstTurnIndex < firstWebSocketIndex,
    standaloneRequestCount: transport.standaloneRequestCount,
  }).toEqual({
    grantStatus: 200,
    turnStatus: 200,
    wssRoomScoped: true,
    wssSecure: true,
    strictOrder: true,
    standaloneRequestCount: 0,
  })
  markSafeStage('protected-transport')

  const participationCookies = (await context.cookies())
    .filter((cookie) => cookie.name === '__Secure-round_access')
  const participationCookie = participationCookies[0]
  const cookieMetadata = {
    count: participationCookies.length,
    domainMatches: participationCookie?.domain === 'baton.localhost',
    httpOnly: participationCookie?.httpOnly === true,
    jwtShape: participationCookie?.value.split('.').length === 3
      && participationCookie.value
        .split('.')
        .every((segment) => /^[A-Za-z0-9_-]+$/.test(segment)),
    pathMatches: participationCookie?.path === `/round/rooms/${roomId}`,
    sameSite: participationCookie?.sameSite,
    secure: participationCookie?.secure === true,
  }
  expect(cookieMetadata).toEqual({
    count: 1,
    domainMatches: true,
    httpOnly: true,
    jwtShape: true,
    pathMatches: true,
    sameSite: 'Strict',
    secure: true,
  })
  markSafeStage('cookie-metadata')
  expect(await page.evaluate(() => ({
    participationCookieVisible: document.cookie.includes('__Secure-round_access='),
    sessionCookieVisible: document.cookie.includes('__Host-baton_session='),
  }))).toEqual({
    participationCookieVisible: false,
    sessionCookieVisible: false,
  })
  markSafeStage('cookie-visibility')
})
