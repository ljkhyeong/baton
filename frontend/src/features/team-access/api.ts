import type { operations } from '@/generated/api'
import { getCsrfToken } from '@/features/auth/api'
import { apiRequest } from '@/shared/api/client'
import { isInstant, isJsonObject, isSameUuid, isUuid } from '@/shared/api/responseValidation'

export type MyTeams = operations['getMyTeams']['responses'][200]['content']['application/json']
export type TeamAccess = operations['getTeamAccess']['responses'][200]['content']['application/json']
export type Permission = NonNullable<TeamAccess['permission']>
export type InvitationPreview = operations['previewTeamInvitation']['responses'][200]['content']['application/json']
type Accepted = operations['acceptTeamInvitation']['responses'][200]['content']['application/json']
type CreatedResponse = operations['createTeamInvitation']['responses'][200]['content']['application/json']
type Created = Omit<CreatedResponse, 'invitation' | 'token'> & {
  invitation: NonNullable<CreatedResponse['invitation']>
  token: string
}
export type AccessScope = { teamId: string; accountId: string; accessKey: string }
export const permissionNames = { ADMIN: '관리자', MEMBER: '구성원', VIEWER: '열람자' } as const
const permission = (value: unknown): value is Permission => value === 'ADMIN' || value === 'MEMBER' || value === 'VIEWER'
const nullableUuid = (value: unknown) => value === null || isUuid(value)
const nullableInstant = (value: unknown) => value === null || isInstant(value)
function invalid(): never { throw new Error('초대·권한 응답을 읽지 못했습니다. 다시 불러와 주세요.') }
function decodeAccess(value: unknown, scope: AccessScope): TeamAccess {
  if (!isJsonObject(value) || !isSameUuid(value.teamId, scope.teamId) || !isSameUuid(value.accountId, scope.accountId)
    || typeof value.accountAccessEnabled !== 'boolean' || !nullableUuid(value.memberId)
    || !(value.permission === null || permission(value.permission))
    || !Array.isArray(value.members) || !value.members.every((m: unknown) => isJsonObject(m)
      && isUuid(m.memberId) && typeof m.memberName === 'string' && typeof m.active === 'boolean'
      && nullableUuid(m.accountId) && (m.permission === null || permission(m.permission)))
    || !Array.isArray(value.invitations) || !value.invitations.every((i: unknown) => isJsonObject(i)
      && isUuid(i.id) && isUuid(i.memberId) && permission(i.permission) && isInstant(i.createdAt) && isInstant(i.expiresAt)
      && nullableInstant(i.acceptedAt) && nullableInstant(i.revokedAt))
    || !Array.isArray(value.audit) || !value.audit.every((a: unknown) => isJsonObject(a) && isUuid(a.id)
      && isUuid(a.actorAccountId) && isUuid(a.memberId) && typeof a.action === 'string'
      && (a.previousPermission === null || permission(a.previousPermission)) && (a.permission === null || permission(a.permission))
      && isInstant(a.changedAt))) invalid()
  return value as TeamAccess
}
const path = (teamId: string) => `/api/v1/team-access/${encodeURIComponent(teamId)}`
export function getTeamAccess(scope: AccessScope) {
  return apiRequest(path(scope.teamId), { method: 'GET', headers: { 'X-Baton-Access-Key': scope.accessKey }, decode: value => decodeAccess(value, scope) })
}
async function headers() { const csrf = await getCsrfToken(); return { [csrf.csrfHeaderName]: csrf.csrfToken } }
export async function activateTeamAccess(scope: AccessScope, memberId: string, recoveryKey: string) {
  return apiRequest(`${path(scope.teamId)}/activate`, { method: 'POST',
    headers: { ...await headers(), 'X-Baton-Recovery-Key': recoveryKey },
    body: { expectedAccountId: scope.accountId, memberId }, decode: value => decodeAccess(value, scope) })
}
export async function changeTeamPermission(scope: AccessScope, memberId: string, permission: Permission | null) {
  return apiRequest(`${path(scope.teamId)}/members/${encodeURIComponent(memberId)}/permission`, { method: 'PUT',
    headers: await headers(), body: { expectedAccountId: scope.accountId, permission }, decode: value => decodeAccess(value, scope) })
}
export async function createTeamInvitation(scope: AccessScope, memberId: string, permission: Permission): Promise<Created> {
  return apiRequest(`${path(scope.teamId)}/invitations`, { method: 'POST', headers: await headers(),
    body: { expectedAccountId: scope.accountId, memberId, permission }, decode: value => {
      if (!isJsonObject(value) || typeof value.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value.token)
        || !isJsonObject(value.invitation) || !isUuid(value.invitation.id) || !isSameUuid(value.invitation.memberId, memberId)
        || value.invitation.permission !== permission || !isInstant(value.invitation.createdAt) || !isInstant(value.invitation.expiresAt)
        || value.invitation.acceptedAt !== null || value.invitation.revokedAt !== null) invalid()
      return value as Created
    } })
}
export async function revokeTeamInvitation(scope: AccessScope, invitationId: string) {
  return apiRequest(`${path(scope.teamId)}/invitations/${encodeURIComponent(invitationId)}/revoke`, { method: 'POST',
    headers: await headers(), body: { expectedAccountId: scope.accountId }, decode: value => decodeAccess(value, scope) })
}
export async function previewTeamInvitation(accountId: string, token: string): Promise<InvitationPreview> {
  return apiRequest('/api/v1/team-invitations/preview', { method: 'POST', headers: await headers(),
    body: { expectedAccountId: accountId, token }, decode: value => {
      if (!isJsonObject(value) || !isUuid(value.teamId) || !isUuid(value.memberId) || typeof value.teamName !== 'string'
        || typeof value.memberName !== 'string' || !permission(value.permission) || !isInstant(value.expiresAt)) invalid()
      return value as InvitationPreview
    } })
}
export async function acceptTeamInvitation(accountId: string, token: string, preview: InvitationPreview): Promise<Accepted> {
  return apiRequest('/api/v1/team-invitations/accept', { method: 'POST', headers: await headers(),
    body: { expectedAccountId: accountId, token }, decode: value => {
      if (!isJsonObject(value) || !isSameUuid(value.accountId, accountId) || !isSameUuid(value.teamId, preview.teamId)
        || !isSameUuid(value.memberId, preview.memberId) || !isUuid(value.seasonId) || !permission(value.permission)) invalid()
      return value as Accepted
    } })
}

export function getMyTeams(accountId: string): Promise<MyTeams> {
  return apiRequest('/api/v1/team-access/mine', { method: 'GET', decode: value => {
    if (!isJsonObject(value) || !isSameUuid(value.accountId, accountId) || !Array.isArray(value.teams)
      || !value.teams.every((team: unknown) => isJsonObject(team) && isUuid(team.teamId) && isUuid(team.memberId)
        && isUuid(team.seasonId) && typeof team.teamName === 'string' && typeof team.memberName === 'string'
        && typeof team.seasonName === 'string' && permission(team.permission) && typeof team.seasonEnded === 'boolean')
      || new Set(value.teams.map(team => (team as { teamId: string }).teamId.toLowerCase())).size !== value.teams.length) invalid()
    return value as MyTeams
  } })
}
