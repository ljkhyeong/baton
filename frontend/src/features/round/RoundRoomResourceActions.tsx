import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useAuthSession } from '@/features/auth/useAuthSession'
import { useCurrentAccountMembership } from '@/features/membership/queries'
import {
  createOrReuseRoundRoomMapping,
  endRoundRoomMapping,
} from '@/features/round/api'
import {
  roundRoomMappingKeys,
  useCurrentRoundRoomMappings,
} from '@/features/round/queries'
import {
  forgetRoundRoomEntryContext,
  rememberRoundRoomMapping,
} from '@/features/round/storage'
import type { RoundRoomMapping, RoundRoomMappingScope } from '@/features/round/types'
import WorkspaceLoginLink from '@/features/workspace/WorkspaceLoginLink'
import { isSameUuid } from '@/shared/api/responseValidation'

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
  const mappingsScope = { accessKey, seasonId, teamId }
  const sessionQuery = useAuthSession()
  const accountId = sessionQuery.data?.authenticated
    ? sessionQuery.data.accountId
    : ''
  const membershipQuery = useCurrentAccountMembership({ accountId, teamId, accessKey })
  const currentMappingsQuery = useCurrentRoundRoomMappings(
    accountId,
    mappingsScope,
    membershipQuery.data?.claimed === true,
  )
  const queryClient = useQueryClient()
  const currentMappingsQueryKey = roundRoomMappingKeys.current(accountId, mappingsScope)
  const [storageError, setStorageError] = useState('')
  const refreshCurrentMappings = () => queryClient.invalidateQueries({
    queryKey: currentMappingsQueryKey,
    exact: true,
    refetchType: 'active',
  })

  const enterRoundRoom = (mapping: RoundRoomMapping) => {
    rememberRoundRoomMapping(scope, mapping)
    setStorageError('')
    window.location.assign(`/room/${mapping.roomId}`)
  }

  const mappingMutation = useMutation({
    mutationFn: () => createOrReuseRoundRoomMapping(scope),
    onMutate: async () => {
      await queryClient.cancelQueries({
        queryKey: currentMappingsQueryKey,
        exact: true,
      })
    },
    onSuccess: refreshCurrentMappings,
    onError: refreshCurrentMappings,
  })
  const endMutation = useMutation({
    mutationFn: (roomId: string) => endRoundRoomMapping(scope, roomId),
    onMutate: async () => {
      await queryClient.cancelQueries({
        queryKey: currentMappingsQueryKey,
        exact: true,
      })
    },
    onSuccess: async (mapping) => {
      await refreshCurrentMappings()
      if (!forgetRoundRoomEntryContext(scope, mapping.roomId)) {
        setStorageError('방은 종료했지만 이 브라우저의 입장 정보를 지우지 못했습니다. 브라우저 저장을 확인해 주세요.')
        return
      }
      setStorageError('')
    },
    onError: refreshCurrentMappings,
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
    return (
      <WorkspaceLoginLink
        className="round-room-text-action"
        teamId={teamId}
        seasonId={seasonId}
        accessKey={accessKey}
      >
        로그인 후 ROUND 시작
      </WorkspaceLoginLink>
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
        내 이름 선택 후 ROUND 시작
      </button>
    )
  }

  if (currentMappingsQuery.isPending) {
    return <small className="round-room-status" role="status">ROUND 연결 확인 중</small>
  }

  if (currentMappingsQuery.isError) {
    return (
      <div className="round-room-resource-actions">
        <button
          type="button"
          className="round-room-text-action"
          disabled={currentMappingsQuery.isFetching}
          onClick={() => void currentMappingsQuery.refetch()}
        >
          {currentMappingsQuery.isFetching ? 'ROUND 연결 다시 확인 중' : 'ROUND 연결 다시 확인'}
        </button>
        <small className="round-room-error" role="alert">
          {errorMessage(currentMappingsQuery.error)}
        </small>
      </div>
    )
  }

  const currentMapping = currentMappingsQuery.data.mappings.find((mapping) => (
    isSameUuid(mapping.resourceId, resourceId)
  )) ?? null
  const busy = mappingMutation.isPending
    || endMutation.isPending
  const visibleError = storageError
    || (mappingMutation.isError ? errorMessage(mappingMutation.error) : '')
    || (endMutation.isError ? errorMessage(endMutation.error) : '')

  return (
    <div className="round-room-resource-actions">
      <button
        type="button"
        className="round-room-primary-action"
        disabled={changesDisabled || busy}
        onClick={() => currentMapping
          ? enterRoundRoom(currentMapping)
          : mappingMutation.mutate(undefined, { onSuccess: enterRoundRoom })}
      >
        {mappingMutation.isPending
          ? 'ROUND 준비 중'
          : currentMapping
            ? 'ROUND 입장'
            : 'ROUND 시작'}
      </button>
      {currentMapping && (
        <button
          type="button"
          className="round-room-text-action"
          disabled={changesDisabled || busy}
          onClick={() => {
            if (!window.confirm('이 ROUND 방을 종료할까요? 종료하면 이 방에 다시 입장할 수 없습니다.')) return
            endMutation.mutate(currentMapping.roomId)
          }}
        >
          {endMutation.isPending ? '종료 중' : 'ROUND 종료'}
        </button>
      )}
      {currentMapping && changesDisabled && (
        <small className="round-room-status">
          종료된 시즌에서는 ROUND 방에 입장하거나 종료할 수 없습니다.
        </small>
      )}
      {visibleError && <small className="round-room-error" role="alert">{visibleError}</small>}
    </div>
  )
}
