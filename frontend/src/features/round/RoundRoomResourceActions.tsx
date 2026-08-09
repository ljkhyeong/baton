import { useMutation } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import {
  createOrReuseRoundRoomMapping,
  endRoundRoomMapping,
} from '@/features/round/api'
import {
  forgetRoundRoomEntryContext,
  readRoundRoomEntryContext,
  rememberRoundRoomMapping,
} from '@/features/round/storage'
import type {
  RoundRoomEntryContext,
  RoundRoomMappingScope,
} from '@/features/round/types'

function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : 'ROUND 방 요청을 처리하지 못했습니다.'
}

export function RoundRoomResourceActions({
  accessKey,
  changesDisabled,
  resourceId,
  seasonId,
  teamId,
  onManageMembership,
}: RoundRoomMappingScope & {
  changesDisabled: boolean
  onManageMembership: () => void
}) {
  const scope = { accessKey, resourceId, seasonId, teamId }
  const sessionQuery = useAuthSession()
  const accountId = sessionQuery.data?.authenticated
    ? sessionQuery.data.accountId
    : ''
  const membershipQuery = useCurrentAccountMembership({ accountId, teamId, accessKey })
  const [entryContext, setEntryContext] = useState<RoundRoomEntryContext | null>(
    () => readRoundRoomEntryContext(scope),
  )
  const [storageError, setStorageError] = useState('')

  useEffect(() => {
    setEntryContext(readRoundRoomEntryContext(scope))
    setStorageError('')
  }, [resourceId, seasonId, teamId])

  const mappingMutation = useMutation({
    mutationFn: () => createOrReuseRoundRoomMapping(scope),
    onSuccess: (mapping) => {
      if (rememberRoundRoomMapping(scope, mapping)) {
        setEntryContext({
          version: 1,
          resourceId: mapping.resourceId,
          roomId: mapping.roomId,
          seasonId: mapping.seasonId,
          teamId: mapping.teamId,
        })
      }
      setStorageError('')
      window.location.assign(`/room/${mapping.roomId}`)
    },
  })
  const endMutation = useMutation({
    mutationFn: (roomId: string) => endRoundRoomMapping(scope, roomId),
    onSuccess: (mapping) => {
      setEntryContext(null)
      if (!forgetRoundRoomEntryContext(scope, mapping.roomId)) {
        setStorageError('방은 종료했지만 이 브라우저의 입장 정보를 지우지 못했습니다. 브라우저 저장을 확인해 주세요.')
        return
      }
      setStorageError('')
    },
  })

  if (sessionQuery.isPending) {
    return <small className="round-room-status" role="status">계정 확인 중</small>
  }

  if (sessionQuery.isError && sessionQuery.data === undefined) {
    return (
      <button
        type="button"
        className="round-room-text-action"
        disabled={sessionQuery.isFetching}
        onClick={() => void sessionQuery.refetch()}
      >
        {sessionQuery.isFetching ? '계정 다시 확인 중' : '계정 상태 다시 확인'}
      </button>
    )
  }

  if (!sessionQuery.data?.authenticated) {
    const returnTo = `/teams/${teamId}/seasons/${seasonId}`
    return (
      <Link
        className="round-room-text-action"
        to={`/login?${new URLSearchParams({ returnTo })}`}
      >
        로그인 후 ROUND 시작
      </Link>
    )
  }

  if (membershipQuery.isPending) {
    return <small className="round-room-status" role="status">연결 확인 중</small>
  }

  if (membershipQuery.isError) {
    return (
      <button
        type="button"
        className="round-room-text-action"
        disabled={membershipQuery.isFetching}
        onClick={() => void membershipQuery.refetch()}
      >
        {membershipQuery.isFetching ? '연결 다시 확인 중' : '구성원 연결 다시 확인'}
      </button>
    )
  }

  if (!membershipQuery.data?.claimed) {
    return (
      <button
        type="button"
        className="round-room-text-action"
        onClick={onManageMembership}
      >
        계정 연결 후 ROUND 시작
      </button>
    )
  }

  const busy = mappingMutation.isPending || endMutation.isPending
  const visibleError = storageError
    || (mappingMutation.isError ? errorMessage(mappingMutation.error) : '')
    || (endMutation.isError ? errorMessage(endMutation.error) : '')

  return (
    <div className="round-room-resource-actions">
      <button
        type="button"
        className="round-room-primary-action"
        disabled={changesDisabled || busy}
        onClick={() => mappingMutation.mutate()}
      >
        {mappingMutation.isPending
          ? 'ROUND 준비 중'
          : entryContext
            ? 'ROUND 입장'
            : 'ROUND 시작'}
      </button>
      {entryContext && (
        <button
          type="button"
          className="round-room-text-action"
          disabled={busy}
          onClick={() => {
            if (!window.confirm('이 ROUND 방을 종료할까요? 종료하면 같은 방 ID로 다시 입장할 수 없습니다.')) return
            endMutation.mutate(entryContext.roomId)
          }}
        >
          {endMutation.isPending ? '종료 중' : 'ROUND 종료'}
        </button>
      )}
      {visibleError && <small className="round-room-error" role="alert">{visibleError}</small>}
    </div>
  )
}
