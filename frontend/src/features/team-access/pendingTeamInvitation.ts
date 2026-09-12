const STORAGE_KEY = 'baton:team-invitation:v1'
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/

export function readStoredTeamInvitationToken() {
  try {
    const token = window.sessionStorage.getItem(STORAGE_KEY) ?? ''
    return TOKEN_PATTERN.test(token) ? token : ''
  } catch { return '' }
}

export function readTeamInvitationToken(hash: string) {
  const token = new URLSearchParams(hash.slice(1)).get('invite')
  return token === null ? readStoredTeamInvitationToken() : TOKEN_PATTERN.test(token) ? token : ''
}

export function storeTeamInvitationToken(token: string) {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, token)
    return true
  } catch { return false }
}

export function clearTeamInvitationToken() {
  try { window.sessionStorage.removeItem(STORAGE_KEY) } catch { /* 탭을 닫으면 저장한 초대가 제거된다. */ }
}
