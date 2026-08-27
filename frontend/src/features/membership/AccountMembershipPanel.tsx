import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuthSession } from '@/features/auth/useAuthSession'
import {
  useClaimAccountMembership,
  useCurrentAccountMembership,
} from '@/features/membership/queries'
import type { Member } from '@/features/workspace/types'
import {
  isActiveMember,
  memberDisplayName,
} from '@/features/workspace/workspacePresentation'

type AccountMembershipPanelProps = {
  teamId: string
  seasonId: string
  accessKey: string
  members: Member[]
  changesDisabled: boolean
  seasonEnded: boolean
}

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : '계정 연결 상태를 확인하지 못했습니다.'
}

export default function AccountMembershipPanel({
  teamId,
  seasonId,
  accessKey,
  members,
  changesDisabled,
  seasonEnded,
}: AccountMembershipPanelProps) {
  const sessionQuery = useAuthSession()
  const accountId = sessionQuery.data?.authenticated
    ? sessionQuery.data.accountId
    : ''
  const scope = { accountId, teamId, accessKey }
  const membershipQuery = useCurrentAccountMembership(scope)
  const claimMutation = useClaimAccountMembership(scope)
  const activeMembers = members.filter(isActiveMember)
  const [memberId, setMemberId] = useState(activeMembers[0]?.id ?? '')
  const selectedMemberId = activeMembers.some((member) => member.id === memberId)
    ? memberId
    : activeMembers[0]?.id ?? ''

  if (sessionQuery.isPending) {
    return (
      <section className="account-membership-panel" aria-busy="true">
        <strong>내 계정 연결</strong>
        <p>로그인 상태를 확인하고 있습니다.</p>
      </section>
    )
  }

  if (sessionQuery.isError && sessionQuery.data === undefined) {
    return (
      <section className="account-membership-panel">
        <strong>내 계정 연결</strong>
        <div className="account-membership-error" role="alert">
          <p>로그인 상태를 확인하지 못했습니다. 연결 상태를 추측하지 않고 다시 확인해 주세요.</p>
          <button
            type="button"
            disabled={sessionQuery.isFetching}
            onClick={() => void sessionQuery.refetch()}
          >
            {sessionQuery.isFetching ? '다시 확인 중' : '로그인 상태 다시 확인'}
          </button>
        </div>
      </section>
    )
  }

  if (!sessionQuery.data?.authenticated) {
    const loginPath = `/login?${new URLSearchParams({
      returnTo: `${window.location.pathname}${window.location.search}`,
    })}`
    return (
      <section className="account-membership-panel">
        <strong>내 계정 연결</strong>
        <p>ROUND 참여 권한을 이어서 사용하려면 로그인한 뒤 기존 구성원 한 명과 계정을 연결하세요.</p>
        <Link className="secondary-button" to={loginPath}>로그인하고 연결하기</Link>
      </section>
    )
  }

  if (membershipQuery.isPending) {
    return (
      <section className="account-membership-panel" aria-busy="true">
        <strong>내 계정 연결</strong>
        <p>이 팀의 계정 연결 상태를 확인하고 있습니다.</p>
      </section>
    )
  }

  const membership = membershipQuery.data
  if (membership?.claimed) {
    const claimedMember = members.find(
      (member) => member.id === membership.memberId,
    )
    return (
      <section className="account-membership-panel account-membership-panel-connected">
        <strong>내 계정이 연결되어 있습니다.</strong>
        <p>
          {claimedMember
            ? `${memberDisplayName(claimedMember)} 구성원으로 연결되었습니다.`
            : '현재 목록에 없는 이전 구성원과 연결되어 있습니다.'}
        </p>
        <small>구성원 활동이 종료되어도 연결 이력은 유지됩니다.</small>
      </section>
    )
  }

  return (
    <section className="account-membership-panel">
      <strong>내 계정 연결</strong>
      {changesDisabled && (
        <p className="account-membership-warning">
          {seasonEnded
            ? '종료된 시즌은 읽기 전용입니다. 활동 중인 시즌에서 계정을 연결해 주세요.'
            : '다른 변경과 충돌해 최신 상태를 확인하는 중입니다. 복구가 끝난 뒤 다시 연결해 주세요.'}
        </p>
      )}
      {membershipQuery.isError
        ? (
            <div className="account-membership-error" role="alert">
              <p>{errorMessage(membershipQuery.error)}</p>
              <button type="button" onClick={() => membershipQuery.refetch()}>다시 확인</button>
            </div>
          )
        : activeMembers.length > 0
          ? (
              <>
                <label>
                  <span>연결할 구성원</span>
                  <select
                    value={selectedMemberId}
                    disabled={changesDisabled || claimMutation.isPending}
                    onChange={(event) => setMemberId(event.target.value)}
                  >
                    {activeMembers.map((member) => (
                      <option key={member.id} value={member.id}>{member.name}</option>
                    ))}
                  </select>
                </label>
                <p className="account-membership-warning">
                  연결한 구성원은 다른 계정이 다시 claim할 수 없습니다. 실제 본인인지 확인한 뒤 진행하세요.
                </p>
                <button
                  type="button"
                  className="primary-button"
                  disabled={changesDisabled || !selectedMemberId || claimMutation.isPending}
                  onClick={() => {
                    if (!window.confirm('선택한 구성원과 이 계정을 연결할까요? 연결 후에는 다른 구성원으로 바꿀 수 없습니다.')) return
                    claimMutation.mutate({
                      teamId,
                      seasonId,
                      memberId: selectedMemberId,
                    })
                  }}
                >
                  {claimMutation.isPending ? '계정 연결 중…' : '선택한 구성원과 연결'}
                </button>
                {claimMutation.isError && (
                  <p className="form-error" role="alert">{errorMessage(claimMutation.error)}</p>
                )}
              </>
            )
          : (
              <p>{changesDisabled
                ? '연결할 활동 중 구성원이 없습니다.'
                : '연결할 활동 중 구성원이 없습니다. 구성원을 먼저 추가하거나 다시 활성화해 주세요.'}</p>
            )}
    </section>
  )
}
