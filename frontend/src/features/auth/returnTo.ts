import {
  ROUND_ROOM_ID_PATTERN_SOURCE,
  UUID_PATTERN_SOURCE,
  isUuid,
} from '@/shared/api/responseValidation'

const WORKSPACE_ROUTE_PATTERN = new RegExp(
  `^/teams/${UUID_PATTERN_SOURCE}/seasons/${UUID_PATTERN_SOURCE}$`,
)
const ROUND_ROOM_ROUTE_PATTERN = new RegExp(`^/room/${ROUND_ROOM_ID_PATTERN_SOURCE}$`)
const RETURN_TO_STORAGE_KEY = 'baton-auth-return-to:v1'

type WorkspaceAuthReturnTo = `/teams/${string}/seasons/${string}`
type RoundRoomAuthReturnTo = `/room/${string}`
type AuthReturnTo = WorkspaceAuthReturnTo | RoundRoomAuthReturnTo

export function safeAuthReturnTo(
  candidate: string | null | undefined,
): AuthReturnTo | null {
  if (!candidate
    || !candidate.startsWith('/')
    || candidate.startsWith('//')
    || candidate.includes('\\')) return null

  const parsed = URL.parse(candidate, 'https://baton.invalid')
  if (!parsed
    || parsed.origin !== 'https://baton.invalid'
    || parsed.hash) return null
  if (ROUND_ROOM_ROUTE_PATTERN.test(parsed.pathname)) return parsed.search ? null : parsed.pathname as AuthReturnTo
  if (!WORKSPACE_ROUTE_PATTERN.test(parsed.pathname)) return null
  if (!parsed.search) return parsed.pathname as AuthReturnTo
  const entries = [...parsed.searchParams]
  const entry = entries[0]
  if (entries.length !== 1 || !entry || entry[0] !== 'brief' || !isUuid(entry[1])) return null
  return `${parsed.pathname}?${new URLSearchParams({ brief: entry[1] })}` as AuthReturnTo
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
