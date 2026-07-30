import { useEffect, useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { isVerifiedJsonCleanupComplete } from '@/shared/lib/durableStorage'
import { Icon } from '@/shared/ui/Icon'
import { useFocusBoundary } from '@/features/workspace/useFocusBoundary'
import { identityEndpoints } from './contract'
import {
  clearPendingMemberInvitation,
  pendingMemberInvitation,
  preparePendingMemberInvitation,
  runWithMemberInvitationLock,
} from './pendingMemberInvitation'
import type { PendingMemberInvitation } from './pendingMemberInvitation'
import {
  formatIdentityInstant,
  identityErrorMessage,
} from './presentation'
import {
  useIdentitySessionQuery,
  useIssueMemberInvitationMutation,
  useMemberInvitationsQuery,
  useRevokeMemberInvitationMutation,
  useTeamMembershipQuery,
} from './queries'

export type IdentityMember = {
  id: string
  name: string
  deactivatedAt?: string | null
}

type TeamIdentityModalProps = {
  teamId: string
  members: IdentityMember[]
  onClose: () => void
}

function memberName(members: IdentityMember[], memberId: string) {
  return members.find((member) => member.id === memberId)?.name ?? memberId
}

export default function TeamIdentityModal({
  teamId,
  members,
  onClose,
}: TeamIdentityModalProps) {
  const dialogRef = useRef<HTMLElement>(null)
  const firstHeadingRef = useRef<HTMLHeadingElement>(null)
  const titleId = useId()
  const descriptionId = useId()
  const sessionQuery = useIdentitySessionQuery()
  const session = sessionQuery.data
  const accountId = session?.authenticated ? (session.accountId ?? '') : ''
  const activeAccountIdRef = useRef(accountId)
  const previousAccountIdRef = useRef(accountId)
  activeAccountIdRef.current = accountId
  const membershipQuery = useTeamMembershipQuery(teamId, accountId)
  const isOwner = Boolean(
    session?.authenticated
    && accountId
    && membershipQuery.data?.accountId === accountId
    && membershipQuery.data.role === 'OWNER',
  )
  const invitationsQuery = useMemberInvitationsQuery(teamId, accountId, isOwner)
  const issueMutation = useIssueMemberInvitationMutation(teamId, accountId)
  const revokeMutation = useRevokeMemberInvitationMutation(teamId, accountId)
  const [selectedMemberId, setSelectedMemberId] = useState('')
  const [pendingRecord, setPendingRecord] = useState<PendingMemberInvitation | null>(
    () => pendingMemberInvitation(teamId),
  )
  const [issuedRecord, setIssuedRecord] = useState<PendingMemberInvitation | null>(null)
  const [localError, setLocalError] = useState('')
  const [statusMessage, setStatusMessage] = useState('')
  const [revokingInvitationId, setRevokingInvitationId] = useState('')
  const tokenInputRef = useRef<HTMLInputElement>(null)
  const closeDisabled = issueMutation.isPending || revokeMutation.isPending

  useFocusBoundary({
    active: true,
    closeDisabled,
    containerRef: dialogRef,
    initialFocusRef: firstHeadingRef,
    onClose,
  })

  const activeMembers = members.filter((member) => !member.deactivatedAt)
  const eligibleMembers = activeMembers.filter(
    (member) => member.id !== membershipQuery.data?.memberId,
  )

  useEffect(() => {
    const recoveredMemberId = pendingRecord?.memberId
    const fallbackMemberId = eligibleMembers[0]?.id ?? ''
    setSelectedMemberId((current) => {
      if (current && eligibleMembers.some((member) => member.id === current)) return current
      if (recoveredMemberId && eligibleMembers.some((member) => member.id === recoveredMemberId)) {
        return recoveredMemberId
      }
      return fallbackMemberId
    })
  }, [eligibleMembers, pendingRecord?.memberId])

  useEffect(() => {
    const refreshPendingRecord = (event: StorageEvent) => {
      if (event.storageArea !== window.localStorage) return
      setPendingRecord(pendingMemberInvitation(teamId))
    }
    window.addEventListener('storage', refreshPendingRecord)
    return () => window.removeEventListener('storage', refreshPendingRecord)
  }, [teamId])

  useEffect(() => {
    const previousAccountId = previousAccountIdRef.current
    previousAccountIdRef.current = accountId
    if (previousAccountId === accountId) return
    setIssuedRecord(null)
    setLocalError('')
    setStatusMessage('')
    setRevokingInvitationId('')
    issueMutation.reset()
    revokeMutation.reset()
  }, [accountId])

  const issueInvitation = async (event: FormEvent) => {
    event.preventDefault()
    setLocalError('')
    setStatusMessage('')
    issueMutation.reset()
    if (!selectedMemberId) {
      setLocalError('초대할 활성 구성원을 선택해 주세요.')
      return
    }

    const lockResult = await runWithMemberInvitationLock(teamId, async () => {
      const prepared = preparePendingMemberInvitation(teamId, selectedMemberId)
      if (prepared.status === 'different-member') {
        return { status: 'different-member' as const, record: prepared.record }
      }
      if (prepared.status === 'storage-unavailable') {
        return { status: 'storage-unavailable' as const }
      }
      setPendingRecord(prepared.record)
      const issued = await issueMutation.mutateAsync({
        memberId: prepared.record.memberId,
        idempotencyKey: prepared.record.idempotencyKey,
      })
      return {
        status: 'issued' as const,
        record: prepared.record,
        issued,
        recovered: prepared.recovered,
      }
    })

    if (lockResult.status === 'busy') {
      setLocalError('다른 탭에서 이 팀의 초대를 처리하고 있습니다. 잠시 후 다시 시도해 주세요.')
      return
    }
    if (lockResult.status === 'unsupported') {
      setLocalError('이 브라우저에서는 안전한 중복 방지 기능을 사용할 수 없어 초대를 발급하지 않았습니다.')
      return
    }
    if (lockResult.status === 'failed') {
      setLocalError(identityErrorMessage(lockResult.error))
      return
    }
    if (activeAccountIdRef.current !== accountId) {
      setIssuedRecord(null)
      issueMutation.reset()
      return
    }
    if (lockResult.value.status === 'different-member') {
      setPendingRecord(lockResult.value.record)
      setSelectedMemberId(lockResult.value.record.memberId)
      setLocalError(
        `${memberName(members, lockResult.value.record.memberId)}님의 이전 요청 결과를 먼저 다시 확인하거나 기록을 정리해 주세요.`,
      )
      return
    }
    if (lockResult.value.status === 'storage-unavailable') {
      setLocalError('안전한 재시도 기록을 저장하지 못해 초대를 발급하지 않았습니다.')
      return
    }

    setIssuedRecord(lockResult.value.record)
    setStatusMessage(
      lockResult.value.recovered
        ? '이전 요청과 같은 키로 결과를 다시 확인했습니다. 이 화면을 벗어나기 전에 안전하게 전달해 주세요.'
        : '초대를 발급했습니다. 이 화면을 벗어나기 전에 안전하게 전달해 주세요.',
    )
    requestAnimationFrame(() => tokenInputRef.current?.focus())
  }

  const finishIssuedToken = (message: string) => {
    if (!issuedRecord) return
    const cleanup = clearPendingMemberInvitation(issuedRecord)
    if (!isVerifiedJsonCleanupComplete(cleanup)) {
      setLocalError('전달 완료 기록을 안전하게 정리하지 못했습니다. 다시 시도해 주세요.')
      return
    }
    setPendingRecord(null)
    setIssuedRecord(null)
    issueMutation.reset()
    setStatusMessage(message)
  }

  const copyIssuedToken = async () => {
    if (!issueMutation.data?.token || !issuedRecord) return
    setLocalError('')
    try {
      await navigator.clipboard.writeText(issueMutation.data.token)
      finishIssuedToken('초대 토큰을 복사했고 재시도 기록을 정리했습니다.')
    } catch {
      tokenInputRef.current?.focus()
      tokenInputRef.current?.select()
      setLocalError('자동 복사를 허용받지 못했습니다. 선택된 토큰을 직접 복사해 주세요.')
    }
  }

  const confirmPendingHandled = () => {
    const record = issuedRecord ?? pendingRecord
    if (!record) return
    const cleanup = clearPendingMemberInvitation(record)
    if (!isVerifiedJsonCleanupComplete(cleanup)) {
      setLocalError('재시도 기록을 안전하게 정리하지 못했습니다. 다시 시도해 주세요.')
      return
    }
    setPendingRecord(null)
    setIssuedRecord(null)
    issueMutation.reset()
    setLocalError('')
    setStatusMessage('토큰 전달 여부를 확인한 것으로 기록하고 재시도 기록을 정리했습니다.')
  }

  const revokeInvitation = async (invitationId: string, memberId: string) => {
    setLocalError('')
    setStatusMessage('')
    setRevokingInvitationId(invitationId)
    try {
      await revokeMutation.mutateAsync(invitationId)
      const record = pendingRecord
      if (record?.memberId === memberId) {
        const cleanup = clearPendingMemberInvitation(record)
        if (isVerifiedJsonCleanupComplete(cleanup)) {
          setPendingRecord(null)
          setIssuedRecord(null)
          issueMutation.reset()
        }
      }
      setStatusMessage(`${memberName(members, memberId)}님의 초대를 폐기했습니다.`)
    } catch {
      // Mutation feedback is rendered below.
    } finally {
      setRevokingInvitationId('')
    }
  }

  const feedbackError =
    localError
    || (sessionQuery.error ? identityErrorMessage(sessionQuery.error) : '')
    || (membershipQuery.error ? identityErrorMessage(membershipQuery.error) : '')
    || (invitationsQuery.error ? identityErrorMessage(invitationsQuery.error) : '')
    || (issueMutation.error ? identityErrorMessage(issueMutation.error) : '')
    || (revokeMutation.error ? identityErrorMessage(revokeMutation.error) : '')

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (!closeDisabled && event.currentTarget === event.target) onClose()
      }}
    >
      <section
        ref={dialogRef}
        className="modal identity-modal"
        role="dialog"
        aria-modal="true"
        aria-busy={closeDisabled || undefined}
        aria-labelledby={titleId}
        aria-describedby={descriptionId}
        tabIndex={-1}
      >
        <button
          type="button"
          className="modal-close"
          aria-label="계정·초대 닫기"
          disabled={closeDisabled}
          onClick={onClose}
        >
          <Icon name="close" />
        </button>
        <span className="section-kicker">IDENTITY</span>
        <h2 id={titleId} ref={firstHeadingRef} tabIndex={-1}>계정·초대</h2>
        <p id={descriptionId} className="modal-description">
          로그인 계정과 구성원을 연결합니다. 작업 공간 공유 키는 소유자 권한의 증거로 사용하지 않습니다.
        </p>

        <div className="identity-management">
          {sessionQuery.isPending && (
            <p className="identity-session-state" aria-live="polite">로그인 상태를 확인하고 있어요.</p>
          )}

          {session && !session.authenticated && (
            <div className="identity-gate">
              <strong>로그인 계정이 필요해요</strong>
              <p>
                공유 키로 작업 공간을 열었더라도 초대 발급 권한은 생기지 않습니다.
                Google 로그인 후 팀 소유자 계정을 확인합니다.
              </p>
              {session.oidcEnabled
                ? (
                    <a className="primary-button" href={identityEndpoints.googleAuthorization}>
                      Google로 로그인
                    </a>
                  )
                : <span>현재 환경에서는 Google 로그인이 비활성화되어 있습니다.</span>}
            </div>
          )}

          {session?.authenticated && membershipQuery.isPending && (
            <p className="identity-session-state" aria-live="polite">팀 권한을 확인하고 있어요.</p>
          )}

          {session?.authenticated
            && !membershipQuery.isPending
            && membershipQuery.data
            && !isOwner && (
              <div className="identity-gate">
                <strong>소유자만 초대를 관리할 수 있어요</strong>
                <p>현재 계정은 이 팀의 구성원입니다. 팀 소유자에게 초대 발급이나 폐기를 요청해 주세요.</p>
              </div>
            )}

          {session?.authenticated
            && !membershipQuery.isPending
            && membershipQuery.isError && (
              <div className="identity-gate">
                <strong>이 팀에 연결된 계정을 찾지 못했어요</strong>
                <p>작업 공간 공유 키와 로그인 계정 권한은 별도입니다. 소유자 초대로 계정을 먼저 연결해 주세요.</p>
              </div>
            )}

          {isOwner && (
            <>
              <section className="identity-owner-card" aria-labelledby="identity-issue-title">
                <div>
                  <span className="identity-role-badge">OWNER</span>
                  <h3 id="identity-issue-title">구성원 계정 초대</h3>
                  <p>활동 중인 구성원 한 명에게 전달할 일회성 토큰을 발급합니다.</p>
                </div>

                {pendingRecord && !issueMutation.data && (
                  <div className="identity-recovery-notice" role="status">
                    <strong>확인하지 못한 이전 요청이 있어요</strong>
                    <p>
                      {memberName(members, pendingRecord.memberId)}님의 초대를 같은 키로 다시 확인할 수 있습니다.
                    </p>
                    <button type="button" className="text-button" onClick={confirmPendingHandled}>
                      전달 여부를 확인했고 기록 지우기
                    </button>
                  </div>
                )}

                <form className="modal-form identity-issue-form" onSubmit={issueInvitation}>
                  <label htmlFor="identity-member-select">
                    <span>초대할 구성원</span>
                    <select
                      id="identity-member-select"
                      required
                      value={selectedMemberId}
                      onChange={(event) => {
                        setSelectedMemberId(event.target.value)
                        setLocalError('')
                        setStatusMessage('')
                      }}
                      disabled={issueMutation.isPending || Boolean(issueMutation.data)}
                    >
                      {eligibleMembers.length === 0 && <option value="">초대 가능한 구성원 없음</option>}
                      {eligibleMembers.map((member) => (
                        <option key={member.id} value={member.id}>{member.name}</option>
                      ))}
                    </select>
                  </label>
                  <button
                    type="submit"
                    className="primary-button"
                    disabled={
                      !selectedMemberId
                      || issueMutation.isPending
                      || Boolean(issueMutation.data)
                    }
                  >
                    {issueMutation.isPending
                      ? '초대 발급 중…'
                      : pendingRecord
                        ? '같은 요청으로 결과 다시 확인'
                        : '일회성 초대 발급'}
                  </button>
                </form>

                {issueMutation.data && (
                  <div className="identity-issued-token" aria-live="assertive">
                    <strong>이 화면에서만 표시하는 초대 토큰</strong>
                    <p>
                      {memberName(members, issueMutation.data.memberId)} ·
                      {' '}
                      {formatIdentityInstant(issueMutation.data.expiresAt)}까지
                    </p>
                    <input
                      ref={tokenInputRef}
                      type="text"
                      readOnly
                      value={issueMutation.data.token}
                      aria-label="발급된 초대 토큰"
                      onFocus={(event) => event.currentTarget.select()}
                    />
                    <div className="identity-token-actions">
                      <button
                        type="button"
                        className="primary-button"
                        onClick={() => void copyIssuedToken()}
                      >
                        토큰 복사
                      </button>
                      <button
                        type="button"
                        className="secondary-button"
                        onClick={confirmPendingHandled}
                      >
                        전달 완료로 확인하고 지우기
                      </button>
                    </div>
                  </div>
                )}
              </section>

              <section className="identity-invitations" aria-labelledby="identity-list-title">
                <div className="identity-list-heading">
                  <div>
                    <h3 id="identity-list-title">열린 초대</h3>
                    <p>아직 수락되지 않았고 폐기하지 않은 초대입니다.</p>
                  </div>
                  <button
                    type="button"
                    className="text-button"
                    onClick={() => void invitationsQuery.refetch()}
                    disabled={invitationsQuery.isFetching}
                  >
                    {invitationsQuery.isFetching ? '새로고침 중…' : '새로고침'}
                  </button>
                </div>
                {invitationsQuery.data?.length
                  ? (
                      <ul>
                        {invitationsQuery.data.map((invitation) => (
                          <li key={invitation.invitationId}>
                            <span>
                              <strong>{memberName(members, invitation.memberId)}</strong>
                              <small>{formatIdentityInstant(invitation.expiresAt)}까지</small>
                            </span>
                            <button
                              type="button"
                              className="secondary-button"
                              disabled={revokeMutation.isPending}
                              onClick={() =>
                                void revokeInvitation(
                                  invitation.invitationId,
                                  invitation.memberId,
                                )}
                            >
                              {revokingInvitationId === invitation.invitationId ? '폐기 중…' : '초대 폐기'}
                            </button>
                          </li>
                        ))}
                      </ul>
                    )
                  : !invitationsQuery.isPending && (
                      <p className="identity-empty">열린 초대가 없습니다.</p>
                    )}
              </section>
            </>
          )}

          {feedbackError && <p className="form-error" role="alert">{feedbackError}</p>}
          {statusMessage && (
            <p className="form-retry-notice" role="status" aria-live="polite">
              {statusMessage}
            </p>
          )}
        </div>
      </section>
    </div>
  )
}
