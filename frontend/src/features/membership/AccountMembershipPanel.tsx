import { useState } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import {
  useClaimAccountMembership,
  useCurrentAccountMembership,
} from '@/features/membership/queries'
import type { Member } from '@/features/workspace/types'
import WorkspaceLoginLink from '@/features/workspace/WorkspaceLoginLink'
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
        <strong>내 이름 선택</strong>
        <p>로그인 상태를 확인하고 있습니다.</p>
      </section>
    )
  }

  if (sessionQuery.isError) {
    return (
      <section className="account-membership-panel">
        <strong>내 이름 선택</strong>
        <div className="account-membership-error" role="alert">
          <p>로그인 상태를 확인하지 못했습니다. 다시 확인해 주세요.</p>
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
    return (
      <section className="account-membership-panel">
        <strong>내 이름 선택</strong>
        <p>로그인한 뒤 팀에 등록된 본인 이름을 선택해 연결하세요.</p>
        <WorkspaceLoginLink teamId={teamId} seasonId={seasonId} accessKey={accessKey} className="secondary-button">
          로그인하고 연결하기
        </WorkspaceLoginLink>
      </section>
    )
  }

  if (membershipQuery.isPending) {
    return (
      <section className="account-membership-panel" aria-busy="true">
        <strong>내 이름 선택</strong>
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
        <strong>이 팀에서 사용하는 내 이름</strong>
        <p>
          {claimedMember
            ? `내 계정은 ${memberDisplayName(claimedMember)} 이름으로 연결되어 있습니다.`
            : '현재 목록에 없는 이전 구성원과 연결되어 있습니다.'}
        </p>
        <small>구성원 활동이 종료되어도 연결 이력은 유지됩니다.</small>
      </section>
    )
  }

  return (
    <section className="account-membership-panel">
      <strong>내 이름 선택</strong>
      {changesDisabled && (
        <p className="account-membership-warning">
          {seasonEnded
            ? '종료된 시즌은 읽기 전용입니다. 활동 중인 시즌에서 계정을 연결해 주세요.'
            : '다른 사람이 수정한 내용을 불러오고 있습니다. 완료되면 다시 연결해 주세요.'}
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
                  <span>내 이름</span>
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
                  이미 연결된 구성원은 다른 계정에 연결할 수 없습니다. 본인 이름인지 확인하세요.
                </p>
                <button
                  type="button"
                  className="primary-button"
                  disabled={changesDisabled || !selectedMemberId || claimMutation.isPending}
                  onClick={() => {
                    if (!window.confirm('선택한 구성원과 이 계정을 연결할까요? 연결 후에는 다른 구성원으로 바꿀 수 없습니다.')) return
                    claimMutation.mutate({
                      expectedAccountId: accountId,
                      teamId,
                      seasonId,
                      memberId: selectedMemberId,
                    })
                  }}
                >
                  {claimMutation.isPending ? '계정 연결 중…' : '이 이름으로 연결'}
                </button>
                {claimMutation.isError && (
                  <p className="form-error" role="alert">{errorMessage(claimMutation.error)}</p>
                )}
              </>
            )
          : (
              <p>{changesDisabled
                ? '선택할 수 있는 구성원이 없습니다.'
                : '선택할 수 있는 구성원이 없습니다. 구성원을 추가하거나 기존 구성원의 활동을 재개해 주세요.'}</p>
            )}
    </section>
  )
}
