import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import type { WorkspaceScope } from '@/features/workspace/api'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
import { activateTeamAccess, changeTeamPermission, createTeamInvitation, getTeamAccess,
  permissionNames, revokeTeamInvitation, type AccessScope, type Permission } from './api'
import './team-access.scss'

type InvitationLink = { invitationId: string; url: string }

export function TeamAccessPanel({ scope }: { scope: WorkspaceScope }) {
  const session = useAuthSession()
  const [open, setOpen] = useState(false)
  return <details className="team-access-panel" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary>팀 초대·권한 관리</summary>
    {open && (session.data?.authenticated
      ? <AccessContent key={`${scope.teamId}:${session.data.accountId}`} scope={{ ...scope, accountId: session.data.accountId }} />
      : <p>로그인한 뒤 팀의 초대·권한 설정을 확인할 수 있습니다.</p>)}
  </details>
}
function AccessContent({ scope }: { scope: AccessScope }) {
  const client = useQueryClient()
  const queryKey = ['teams', scope.teamId, 'access', scope.accountId, scope.accessKey]
  const query = useQuery({ queryKey, queryFn: () => getTeamAccess(scope) })
  const [recoveryKey, setRecoveryKey] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [memberId, setMemberId] = useState('')
  const [permission, setPermission] = useState<Permission>('MEMBER')
  const [invitationLink, setInvitationLink] = useState<InvitationLink | null>(null)
  const [copyStatus, setCopyStatus] = useState<'idle' | 'copied' | 'failed'>('idle')
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const invitations = query.data?.invitations
    if (!invitations) return
    const expirations = invitations
      .filter(invitation => !invitation.acceptedAt && !invitation.revokedAt)
      .map(invitation => Date.parse(invitation.expiresAt))
      .sort((left, right) => left - right)
    let timeoutId: number | undefined
    const updateExpiration = () => {
      const current = Date.now()
      setNow(current)
      const next = expirations.find(expiration => expiration > current)
      if (next !== undefined) timeoutId = window.setTimeout(updateExpiration, next - current)
    }
    updateExpiration()
    return () => { if (timeoutId !== undefined) window.clearTimeout(timeoutId) }
  }, [query.data?.invitations])
  const copyInvitationUrl = async () => {
    if (!invitationLink) return
    setCopyStatus('idle')
    if (!navigator.clipboard?.writeText) {
      setCopyStatus('failed')
      return
    }
    try {
      await navigator.clipboard.writeText(invitationLink.url)
      setCopyStatus('copied')
    } catch {
      setCopyStatus('failed')
    }
  }
  const mutation = useMutation({ mutationFn: async (action: { kind: 'activate' | 'invite' | 'revoke' | 'permission'; id?: string; permission?: Permission | null }) => {
    if (action.kind === 'activate') {
      const key = recoveryKey
      setRecoveryKey('')
      return activateTeamAccess(scope, query.data!.memberId!, key)
    }
    if (action.kind === 'invite') {
      const created = await createTeamInvitation(scope, memberId, permission)
      setInvitationLink({ invitationId: created.invitation.id, url: `${window.location.origin}/join#invite=${created.token}` })
      setCopyStatus('idle')
      return getTeamAccess(scope)
    }
    if (action.kind === 'revoke') { setCopyStatus('idle'); return revokeTeamInvitation(scope, action.id!) }
    return changeTeamPermission(scope, action.id!, action.permission ?? null)
  }, onSuccess: data => {
    client.setQueryData(queryKey, data)
    void client.invalidateQueries({ queryKey: ['teams', scope.teamId] })
  } })
  if (query.isPending) return <p role="status">팀 권한을 불러오고 있습니다.</p>
  if (query.isError) return <p role="alert">{query.error.message} <button type="button" onClick={() => void query.refetch()}>다시 불러오기</button></p>
  const access = query.data
  const mine = access.members.find(member => member.memberId === access.memberId)
  const invitationCandidates = access.members.filter(member => member.active && !member.permission)
  const invitationMemberId = invitationCandidates.some(member => member.memberId === memberId) ? memberId : ''
  const visibleInvitationLink = invitationLink && access.invitations.some(invitation =>
    invitation.id.toLowerCase() === invitationLink.invitationId.toLowerCase()
    && !invitation.acceptedAt && !invitation.revokedAt && Date.parse(invitation.expiresAt) > now)
    ? invitationLink : null
  return <div>
    <p>{access.accountAccessEnabled ? `로그인한 계정으로 이용 중입니다. 내 권한: ${access.permission ? permissionNames[access.permission] : '접근 권한 해제'}`
      : '현재는 공유 링크로 이용합니다. 관리자를 지정하면 관리자와 초대받은 계정만 이용할 수 있습니다.'}</p>
    {!access.accountAccessEnabled && (access.memberId ? <form onSubmit={event => {
      event.preventDefault(); if (confirmed && recoveryKey && !mutation.isPending) mutation.mutate({ kind: 'activate' })
    }}>
      <p>{mine?.memberName}님으로 연결된 내 계정을 팀 관리자로 지정합니다. 이후에는 기존 공유 링크를 사용할 수 없고, 다른 사람은 초대를 받아 로그인해야 합니다.</p>
      <label>운영자 복구 키<input type="password" autoComplete="off" value={recoveryKey} onChange={event => setRecoveryKey(event.target.value)} required /></label>
      <label className="team-access-confirm"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} required />기존 공유 링크를 막고 내 계정을 관리자로 지정하는 데 동의합니다.</label>
      <button type="submit" disabled={mutation.isPending || !confirmed || !recoveryKey}>계정 로그인으로 전환</button>
    </form> : <p>먼저 ‘내 이름 선택’에서 본인 이름을 선택하세요. 전환하려면 운영자 복구 키가 필요합니다.</p>)}
    {access.permission === 'ADMIN' && <>
      <h4>구성원 권한</h4>
      <ul>{access.members.map(member => <li key={member.memberId}>
        <span>{member.memberName}{!member.active && ' · 활동 종료'}</span>
        {member.accountId ? <select aria-label={`${member.memberName} 접근 권한`} value={member.permission ?? ''}
          disabled={mutation.isPending || !member.active} onChange={event => {
            const next = event.target.value as Permission | ''
            if (window.confirm(next ? `${member.memberName}님의 접근 권한을 ${permissionNames[next]}로 변경할까요?`
              : `${member.memberName}님의 접근 권한을 해제할까요?`))
              mutation.mutate({ kind: 'permission', id: member.memberId, permission: next || null })
          }}>
          <option value="">접근 권한 해제</option>{Object.entries(permissionNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select> : <span>연결된 계정 없음</span>}
      </li>)}</ul>
      <h4>구성원 초대</h4>
      <form onSubmit={event => { event.preventDefault(); if (invitationMemberId && !mutation.isPending) mutation.mutate({ kind: 'invite' }) }}>
        <label>초대할 구성원<select value={invitationMemberId} required onChange={event => setMemberId(event.target.value)}>
          <option value="">구성원 선택</option>{invitationCandidates.map(member => <option key={member.memberId} value={member.memberId}>{member.memberName}</option>)}
        </select></label>
        <label>초대 권한<select value={permission} onChange={event => setPermission(event.target.value as Permission)}>
          {Object.entries(permissionNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <p>열람자는 조회, 구성원은 업무 기록 변경, 관리자는 구성원·초대·시즌 관리를 할 수 있습니다.</p>
        <button type="submit" disabled={!invitationMemberId || mutation.isPending}>초대 링크 만들기</button>
      </form>
      {visibleInvitationLink && <div><label>생성한 초대 링크<input readOnly value={visibleInvitationLink.url} autoComplete="off" spellCheck={false} onFocus={event => event.currentTarget.select()} /></label>
        <button type="button" onClick={() => void copyInvitationUrl()}>초대 링크 복사</button>
        {copyStatus === 'copied' && <p role="status">초대 링크를 복사했습니다.</p>}
        {copyStatus === 'failed' && <p role="alert">자동으로 복사하지 못했습니다. 위 링크를 선택해 직접 복사해 주세요.</p>}
        <p>초대한 구성원에게 전달하세요. 7일 동안 사용할 수 있습니다. 새 링크를 만들면 같은 구성원의 미수락 초대는 취소됩니다.</p>
      </div>}
      <h4>초대 목록</h4>
      {access.invitations.length === 0 ? <p>아직 만든 초대가 없습니다.</p>
        : <ul>{access.invitations.map(invite => {
          const expired = Date.parse(invite.expiresAt) <= now
          const pending = !invite.acceptedAt && !invite.revokedAt && !expired
          const memberName = access.members.find(member => member.memberId === invite.memberId)?.memberName ?? '알 수 없는 구성원'
          return <li key={invite.id}><span>{memberName} · {permissionNames[invite.permission]}
            <small>{invite.acceptedAt ? '수락 완료' : invite.revokedAt ? '초대 취소' : expired ? '기간 만료' : `${formatInstant(invite.expiresAt)}까지 유효`}</small></span>
            {pending && <button type="button" disabled={mutation.isPending} onClick={() => {
              if (window.confirm(`${memberName}님의 초대를 취소할까요? 이 초대 링크는 즉시 사용할 수 없게 됩니다.`))
                mutation.mutate({ kind: 'revoke', id: invite.id })
            }}>초대 취소</button>}
          </li>
        })}</ul>}
      <details><summary>최근 권한 변경 이력</summary><ul>{access.audit.map(item => <li key={item.id}><span>
        {access.members.find(member => member.memberId === item.memberId)?.memberName} · {({ ADMIN_RECOVERY: '관리자 지정·복구', INVITED: '초대 생성', INVITATION_REVOKED: '초대 취소', INVITATION_ACCEPTED: '초대 수락', PERMISSION_CHANGED: '권한 변경', ACCOUNT_DEACTIVATED: '계정 비활성화' } as Record<string, string>)[item.action] ?? '접근 설정 변경'}
        <small>변경한 사람: {access.members.find(member => member.accountId === item.actorAccountId)?.memberName ?? '연결된 구성원 없음'}</small>
        <small>{item.previousPermission ? permissionNames[item.previousPermission] : '접근 없음'} → {item.permission ? permissionNames[item.permission] : '접근 없음'} · {formatInstant(item.changedAt)}</small>
      </span></li>)}</ul></details>
    </>}
    {mutation.isError && <p role="alert">{mutation.error.message}</p>}
  </div>
}
