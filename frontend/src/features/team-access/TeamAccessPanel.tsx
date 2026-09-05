import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import type { WorkspaceScope } from '@/features/workspace/api'
import { formatInstant } from '@/features/workspace/WorkspaceViews'
import { activateTeamAccess, changeTeamPermission, createTeamInvitation, getTeamAccess,
  permissionNames, revokeTeamInvitation, type AccessScope, type Permission } from './api'
import './team-access.scss'

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
  const [invitationUrl, setInvitationUrl] = useState('')
  const [copied, setCopied] = useState(false)
  const mutation = useMutation({ mutationFn: async (action: { kind: 'activate' | 'invite' | 'revoke' | 'permission'; id?: string; permission?: Permission | null }) => {
    if (action.kind === 'activate') {
      const key = recoveryKey
      setRecoveryKey('')
      return activateTeamAccess(scope, query.data!.memberId!, key)
    }
    if (action.kind === 'invite') {
      const created = await createTeamInvitation(scope, memberId, permission)
      setInvitationUrl(`${window.location.origin}/join#invite=${created.token}`)
      setCopied(false)
      return getTeamAccess(scope)
    }
    if (action.kind === 'revoke') { setInvitationUrl(''); return revokeTeamInvitation(scope, action.id!) }
    return changeTeamPermission(scope, action.id!, action.permission ?? null)
  }, onSuccess: data => {
    client.setQueryData(queryKey, data)
    void client.invalidateQueries({ queryKey: ['teams', scope.teamId] })
  } })
  if (query.isPending) return <p role="status">팀 권한을 불러오고 있습니다.</p>
  if (query.isError) return <p role="alert">{query.error.message} <button type="button" onClick={() => void query.refetch()}>다시 불러오기</button></p>
  const access = query.data
  const mine = access.members.find(member => member.memberId === access.memberId)
  return <div>
    <p>{access.accountAccessEnabled ? `계정 권한으로 접근합니다. 내 권한: ${access.permission ? permissionNames[access.permission] : '접근 취소'}`
      : '현재는 공유 링크로 접근합니다. 운영자가 첫 관리자를 지정하면 계정 권한으로 전환됩니다.'}</p>
    {!access.accountAccessEnabled && (access.memberId ? <form onSubmit={event => {
      event.preventDefault(); if (confirmed && recoveryKey && !mutation.isPending) mutation.mutate({ kind: 'activate' })
    }}>
      <p>{mine?.memberName} 구성원과 연결된 현재 계정을 관리자로 지정합니다. 전환하면 기존 공유 링크 접근이 종료되고 다른 계정은 초대를 받아야 합니다.</p>
      <label>운영자 복구 키<input type="password" autoComplete="off" value={recoveryKey} onChange={event => setRecoveryKey(event.target.value)} required /></label>
      <label className="team-access-confirm"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} required />공유 링크 접근 종료와 현재 계정의 관리자 지정을 확인했습니다.</label>
      <button type="submit" disabled={mutation.isPending || !confirmed || !recoveryKey}>관리자 지정 후 계정 권한으로 전환</button>
    </form> : <p>위의 내 계정 연결에서 본인 구성원을 먼저 연결해 주세요. 전환에는 운영자 복구 키가 필요합니다.</p>)}
    {access.permission === 'ADMIN' && <>
      <h4>구성원 권한</h4>
      <ul>{access.members.map(member => <li key={member.memberId}>
        <span>{member.memberName}{!member.active && ' · 활동 종료'}</span>
        {member.accountId ? <select aria-label={`${member.memberName} 접근 권한`} value={member.permission ?? ''}
          disabled={mutation.isPending || !member.active} onChange={event => {
            const next = event.target.value as Permission | ''
            if (window.confirm(`${member.memberName}님의 접근 권한을 ${next ? permissionNames[next] : '접근 취소'}로 변경할까요?`))
              mutation.mutate({ kind: 'permission', id: member.memberId, permission: next || null })
          }}>
          <option value="">접근 취소</option>{Object.entries(permissionNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select> : <span>계정 연결 대기</span>}
      </li>)}</ul>
      <h4>구성원 초대</h4>
      <form onSubmit={event => { event.preventDefault(); if (memberId && !mutation.isPending) mutation.mutate({ kind: 'invite' }) }}>
        <label>초대할 구성원<select value={memberId} required onChange={event => setMemberId(event.target.value)}>
          <option value="">구성원 선택</option>{access.members.filter(member => member.active && !member.permission).map(member => <option key={member.memberId} value={member.memberId}>{member.memberName}</option>)}
        </select></label>
        <label>초대 권한<select value={permission} onChange={event => setPermission(event.target.value as Permission)}>
          {Object.entries(permissionNames).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select></label>
        <p>열람자는 조회, 구성원은 업무 기록 변경, 관리자는 구성원·초대·시즌 관리를 할 수 있습니다.</p>
        <button type="submit" disabled={!memberId || mutation.isPending}>7일 유효 초대 링크 만들기</button>
      </form>
      {invitationUrl && <div><label>생성한 초대 링크<input readOnly value={invitationUrl} /></label>
        <button type="button" onClick={() => void navigator.clipboard.writeText(invitationUrl).then(() => setCopied(true)).catch(() => setCopied(false))}>{copied ? '복사했습니다' : '초대 링크 복사'}</button>
        <p>원하는 구성원에게 이 링크를 전달하세요. 새 초대를 만들면 같은 구성원의 이전 미수락 링크가 취소됩니다.</p>
      </div>}
      <h4>초대 목록</h4>
      <ul>{access.invitations.map(invite => <li key={invite.id}><span>{access.members.find(member => member.memberId === invite.memberId)?.memberName} · {permissionNames[invite.permission]}<small>{invite.acceptedAt ? '수락 완료' : invite.revokedAt ? '초대 취소' : `${formatInstant(invite.expiresAt)}까지 유효`}</small></span>
        {!invite.acceptedAt && !invite.revokedAt && <button type="button" disabled={mutation.isPending} onClick={() => mutation.mutate({ kind: 'revoke', id: invite.id })}>초대 취소</button>}
      </li>)}</ul>
      <details><summary>최근 권한 변경 이력</summary><ul>{access.audit.map(item => <li key={item.id}><span>
        {access.members.find(member => member.memberId === item.memberId)?.memberName} · {({ ADMIN_RECOVERY: '관리자 지정·복구', INVITED: '초대 생성', INVITATION_REVOKED: '초대 취소', INVITATION_ACCEPTED: '초대 수락', PERMISSION_CHANGED: '권한 변경' } as Record<string, string>)[item.action] ?? '접근 설정 변경'}
        <small>변경한 사람: {access.members.find(member => member.accountId === item.actorAccountId)?.memberName ?? '연결된 구성원 없음'}</small>
        <small>{item.previousPermission ? permissionNames[item.previousPermission] : '접근 없음'} → {item.permission ? permissionNames[item.permission] : '접근 없음'} · {formatInstant(item.changedAt)}</small>
      </span></li>)}</ul></details>
    </>}
    {mutation.isError && <p role="alert">{mutation.error.message}</p>}
  </div>
}
