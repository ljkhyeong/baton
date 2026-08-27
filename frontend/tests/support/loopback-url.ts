const LOOPBACK_HOSTNAMES = new Set(['127.0.0.1', 'localhost', '[::1]'])

export function requireLoopbackHttpOrigin(
  value: string | undefined,
  variableName: string,
): string {
  if (!value) {
    throw new Error(`${variableName}가 필요합니다.`)
  }

  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${variableName}는 명시적 포트가 있는 HTTP loopback origin이어야 합니다.`)
  }

  if (
    url.protocol !== 'http:'
    || !LOOPBACK_HOSTNAMES.has(url.hostname)
    || !url.port
    || url.username
    || url.password
    || (url.pathname !== '/' && url.pathname !== '')
    || url.search
    || url.hash
  ) {
    throw new Error(`${variableName}는 명시적 포트가 있는 HTTP loopback origin이어야 합니다.`)
  }

  return url.origin
}

export function requireRoundEdgeHttpsOrigin(
  value: string | undefined,
  variableName: string,
): string {
  if (!value) {
    throw new Error(`${variableName}가 필요합니다.`)
  }

  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${variableName}는 BATON ROUND 테스트용 HTTPS origin이어야 합니다.`)
  }

  if (
    url.protocol !== 'https:'
    || url.hostname !== 'baton.fullstack.test'
    || url.username
    || url.password
    || (url.pathname !== '/' && url.pathname !== '')
    || url.search
    || url.hash
  ) {
    throw new Error(`${variableName}는 BATON ROUND 테스트용 HTTPS origin이어야 합니다.`)
  }

  return url.origin
}
