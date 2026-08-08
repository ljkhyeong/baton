const WORKSPACE_ROUTE_PATTERN = /^\/teams\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/seasons\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/?$/i
const RETURN_TO_STORAGE_KEY = 'baton-auth-return-to:v1'

export function safeWorkspaceReturnTo(candidate: string | null | undefined) {
  if (!candidate
    || !candidate.startsWith('/')
    || candidate.startsWith('//')
    || candidate.includes('\\')) return null

  try {
    const parsed = new URL(candidate, 'https://baton.invalid')
    if (parsed.origin !== 'https://baton.invalid'
      || parsed.hash
      || parsed.search
      || !WORKSPACE_ROUTE_PATTERN.test(parsed.pathname)) return null
    return parsed.pathname
  } catch {
    return null
  }
}

export function rememberAuthReturnTo(returnTo: string) {
  const safeReturnTo = safeWorkspaceReturnTo(returnTo)
  if (!safeReturnTo) return
  try {
    window.sessionStorage.setItem(RETURN_TO_STORAGE_KEY, safeReturnTo)
  } catch {
    // Session storage가 막혀도 로그인 자체는 시작 화면으로 안전하게 계속한다.
  }
}

export function readRememberedAuthReturnTo() {
  try {
    return safeWorkspaceReturnTo(window.sessionStorage.getItem(RETURN_TO_STORAGE_KEY))
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
