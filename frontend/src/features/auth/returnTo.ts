const UUID_PATTERN = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
const ROUND_ROOM_ID_PATTERN = '[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}-[abcdefghjkmnpqrstuvwxyz23456789]{4}'
const WORKSPACE_ROUTE_PATTERN = new RegExp(`^/teams/${UUID_PATTERN}/seasons/${UUID_PATTERN}$`)
const ROUND_ROOM_ROUTE_PATTERN = new RegExp(`^/room/${ROUND_ROOM_ID_PATTERN}$`)
const RETURN_TO_STORAGE_KEY = 'baton-auth-return-to:v1'

export type WorkspaceAuthReturnTo = `/teams/${string}/seasons/${string}`
export type RoundRoomAuthReturnTo = `/room/${string}`
export type AuthReturnTo = WorkspaceAuthReturnTo | RoundRoomAuthReturnTo

export function safeAuthReturnTo(
  candidate: string | null | undefined,
): AuthReturnTo | null {
  if (!candidate
    || !candidate.startsWith('/')
    || candidate.startsWith('//')
    || candidate.includes('\\')) return null

  try {
    const parsed = new URL(candidate, 'https://baton.invalid')
    if (parsed.origin !== 'https://baton.invalid'
      || parsed.hash
      || parsed.search
      || (!WORKSPACE_ROUTE_PATTERN.test(parsed.pathname)
        && !ROUND_ROOM_ROUTE_PATTERN.test(parsed.pathname))) return null
    return parsed.pathname as AuthReturnTo
  } catch {
    return null
  }
}

export function isRoundRoomAuthReturnTo(
  returnTo: AuthReturnTo | '/',
): returnTo is RoundRoomAuthReturnTo {
  return ROUND_ROOM_ROUTE_PATTERN.test(returnTo)
}

export function rememberAuthReturnTo(returnTo: string) {
  const safeReturnTo = safeAuthReturnTo(returnTo)
  if (!safeReturnTo) return
  try {
    window.sessionStorage.setItem(RETURN_TO_STORAGE_KEY, safeReturnTo)
  } catch {
    // Session storage가 막혀도 로그인 자체는 시작 화면으로 안전하게 계속한다.
  }
}

export function readRememberedAuthReturnTo() {
  try {
    return safeAuthReturnTo(window.sessionStorage.getItem(RETURN_TO_STORAGE_KEY))
  } catch {
    return null
  }
}

export function clearRememberedAuthReturnTo() {
  try {
    window.sessionStorage.removeItem(RETURN_TO_STORAGE_KEY)
  } catch {
    // 저장소 정리 실패는 인증 session 종료 결과를 바꾸지 않는다.
  }
}
