import { useState } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import { isActiveMember } from './workspacePresentation'
import type { Member } from './types'

// 로그인한 계정이 이 팀에서 선택한 구성원 ID를 찾는다. 공유 링크로만 들어오면 알 수 없다.
// resolved는 처음 판단이 끝난 뒤 계속 true로 두어, 로그인 상태를 다시 조회하는 동안 화면을 비우지 않는다.
export function useCurrentMemberId(teamId: string, accessKey: string) {
  const session = useAuthSession()
  const accountId = session.data?.authenticated ? session.data.accountId : ''
  const membership = useCurrentAccountMembership({ accountId, teamId, accessKey })
  const resolvedNow = !session.isPending && (!accountId || !membership.isPending)
  const [resolvedOnce, setResolvedOnce] = useState(resolvedNow)
  if (resolvedNow && !resolvedOnce) setResolvedOnce(true)
  return {
    memberId: membership.data?.claimed ? membership.data.memberId : undefined,
    resolved: resolvedNow || resolvedOnce,
  }
}

export function activeMember(members: Member[], memberId?: string) {
  const member = members.find((candidate) => candidate.id === memberId)
  return member && isActiveMember(member) ? member : undefined
}
